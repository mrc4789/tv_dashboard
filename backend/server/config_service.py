"""TvDashboard Phase 1 configuration service. Python 3.10+, local/staging deployment."""
import argparse
import logging
from contextlib import contextmanager
from collections import defaultdict, deque
from datetime import datetime, timezone, timedelta
import hashlib
import hmac
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import ssl
import threading
import time
from urllib.parse import parse_qs, urlsplit, unquote
import uuid

ROOT = Path(__file__).resolve().parents[1]
ID_PATTERN = re.compile(r'^[a-z0-9-]{1,32}$')

def utcnow():
    return datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')

def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()

class ApiError(Exception):
    def __init__(self, status, code, message, headers=None):
        self.status, self.code, self.message = status, code, message
        self.headers = headers or {}

def validate_url(value, allow_http=True):
    if not isinstance(value, str):
        raise ApiError(400, 'INVALID_URL', 'URL phải là chuỗi.')
    value = value.strip()
    try:
        u = urlsplit(value)
        if (not value or len(value) > 2048 or any(c.isspace() or ord(c)<32 for c in value)
            or u.scheme not in (['https','http'] if allow_http else ['https'])
            or not u.hostname or u.username is not None or u.password is not None
            or any(c in u.netloc for c in '\\<>"') or (u.port is not None and not 1 <= u.port <= 65535)):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise ApiError(400, 'INVALID_URL', 'URL tuyệt đối không hợp lệ; HTTP chỉ dùng trong chế độ phát triển.')
    return value

class ConfigService(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, db_path, seed_path, config, keys, admin_token):
        self.db_path = str(db_path)
        self.config = config
        self.seed_path = Path(seed_path)
        self.admin_hash = digest(admin_token)
        self.lock = threading.RLock()
        self.rate = defaultdict(deque)
        self.event_condition = threading.Condition()
        self.event_sequence = defaultdict(int)
        self.event_slots = threading.BoundedSemaphore(128)
        self.events_closed = False
        self.event_heartbeat_seconds = 15
        self.controls = {'failureStatus': 0, 'delayMs': 0, 'malformedJson': False}
        self.seed = json.loads(self.seed_path.read_text(encoding='utf-8'))['departments']
        self.initialize(keys)
        from admin_portal import init_admin
        init_admin(self)
        super().__init__(address, Handler)

    def publish_settings(self,department):
        with self.event_condition:
            self.event_sequence[department]+=1
            self.event_condition.notify_all()

    def server_close(self):
        pairing = getattr(self, 'lan_pairing', None)
        if pairing: pairing.close()
        with self.event_condition:
            self.events_closed=True
            self.event_condition.notify_all()
        super().server_close()

    @contextmanager
    def connect(self):
        if self.config.get('tursoUrl'):
            from turso_store import connect
            with connect(self.config['tursoUrl'],self.config['tursoToken']) as db:
                yield db
            return
        c = sqlite3.connect(self.db_path, timeout=10)
        c.row_factory = sqlite3.Row
        c.execute('PRAGMA foreign_keys=ON')
        try:
            with c:
                yield c
        finally:
            c.close()

    def initialize(self, keys):
        if not self.config.get('tursoUrl'): Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as c:
            from remote_logs import init as init_diagnostics
            init_diagnostics(c)
            c.executescript('''
            CREATE TABLE IF NOT EXISTS device_load_status (device_id TEXT PRIMARY KEY, state TEXT NOT NULL, observed_at TEXT NOT NULL, url_hash TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS device_bindings (device_id TEXT PRIMARY KEY, key_id TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS device_pins (
              device_id TEXT PRIMARY KEY, policy_json TEXT NOT NULL, updated_by TEXT NOT NULL, updated_at TEXT NOT NULL,
              version INTEGER NOT NULL DEFAULT 1);
            CREATE TABLE IF NOT EXISTS device_pin_ack (
              device_id TEXT PRIMARY KEY, policy_version INTEGER NOT NULL, received_at TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS departments (
              id TEXT PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT,
              kind TEXT NOT NULL, description TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1);
            CREATE TABLE IF NOT EXISTS settings (
              department_id TEXT PRIMARY KEY REFERENCES departments(id),
              dashboard_url TEXT NOT NULL, version INTEGER NOT NULL,
              updated_at TEXT NOT NULL, updated_by TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS api_keys (
              key_id TEXT PRIMARY KEY, key_hash TEXT UNIQUE NOT NULL,
              scope_json TEXT, active INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS devices (
              device_id TEXT PRIMARY KEY, name TEXT NOT NULL,
              department_id TEXT NOT NULL REFERENCES departments(id),
              first_seen_at TEXT NOT NULL, last_seen_at TEXT NOT NULL,
              app_version TEXT, last_key_id TEXT, archived_at TEXT);
            CREATE TABLE IF NOT EXISTS config_audit (
              id INTEGER PRIMARY KEY AUTOINCREMENT, department_id TEXT,
              old_url TEXT, new_url TEXT, old_version INTEGER, new_version INTEGER,
              changed_by TEXT, changed_at TEXT, request_id TEXT);
            CREATE INDEX IF NOT EXISTS idx_devices_last_seen ON devices(last_seen_at);
            ''')
            pin_columns={r['name'] for r in c.execute('PRAGMA table_info(device_pins)')}
            if 'version' not in pin_columns:
                c.execute('ALTER TABLE device_pins ADD COLUMN version INTEGER NOT NULL DEFAULT 1')
            c.execute('CREATE TABLE IF NOT EXISTS service_meta (name TEXT PRIMARY KEY,value TEXT NOT NULL)')
            c.execute("INSERT OR IGNORE INTO service_meta VALUES('databaseEpoch',?)",(str(uuid.uuid4()),))
            self.database_epoch=c.execute("SELECT value FROM service_meta WHERE name='databaseEpoch'").fetchone()[0]
            c.execute("CREATE TABLE IF NOT EXISTS cloud_invites(code_hash TEXT PRIMARY KEY,department TEXT NOT NULL,expires INTEGER NOT NULL,used_device TEXT)")
            previous=c.execute("SELECT value FROM service_meta WHERE name='sampleBaseUrl'").fetchone()
            old_base=previous['value'] if previous else None
            new_base=self.config['dashboardBaseUrl'].rstrip('/')
            for item in self.seed:
                c.execute('INSERT OR IGNORE INTO departments(id,name,parent_id,kind,description) VALUES(?,?,?,?,?)',
                    (item['id'],item['name'],item['parentId'],item['kind'],item['description']))
                url = self.config['dashboardBaseUrl'].rstrip('/') + '/sample-dashboard/' + item['id']
                c.execute('INSERT OR IGNORE INTO settings VALUES(?,?,?,?,?)', (item['id'],url,1,utcnow(),'sample-seed'))
            if old_base and old_base!=new_base:
                for item in self.seed:
                    old_url=old_base+'/sample-dashboard/'+item['id'];new_url=new_base+'/sample-dashboard/'+item['id']
                    row=c.execute('SELECT * FROM settings WHERE department_id=? AND dashboard_url=?',(item['id'],old_url)).fetchone()
                    if row:
                        now=utcnow()
                        c.execute('UPDATE settings SET dashboard_url=?,version=version+1,updated_at=?,updated_by=? WHERE department_id=?',(new_url,now,'network-change',item['id']))
                        c.execute('INSERT INTO config_audit(department_id,old_url,new_url,old_version,new_version,changed_by,changed_at) VALUES(?,?,?,?,?,?,?)',(item['id'],old_url,new_url,row['version'],row['version']+1,'network-change',now))
            c.execute("INSERT INTO service_meta VALUES('sampleBaseUrl',?) ON CONFLICT(name) DO UPDATE SET value=excluded.value",(new_base,))
            for key_id, value, scope in keys:
                c.execute('INSERT INTO api_keys VALUES(?,?,?,1) ON CONFLICT(key_id) DO UPDATE SET key_hash=excluded.key_hash,scope_json=excluded.scope_json,active=CASE WHEN api_keys.key_hash=excluded.key_hash THEN api_keys.active ELSE 1 END',
                    (key_id,digest(value),json.dumps(scope) if scope is not None else None))

    def limit(self, key_id, device_id=None):
        now = time.monotonic()
        limits = [('key:'+key_id, self.config.get('ipRequestsPerMinute', 1200) if key_id.startswith('client:') else self.config['keyRequestsPerMinute'])]
        if device_id:
            limits.append(('device:'+key_id+':'+device_id,self.config['deviceRequestsPerMinute']))
        with self.lock:
            for label, cap in limits:
                if label not in self.rate and len(self.rate)>=5000:
                    for stale in list(self.rate):
                        if not self.rate[stale] or now-self.rate[stale][-1]>=60: del self.rate[stale]
                    if len(self.rate)>=5000: raise ApiError(429,'RATE_LIMITED','Quá nhiều danh tính gọi API.',{'Retry-After':'60'})
                queue = self.rate[label]
                while queue and now-queue[0]>=60: queue.popleft()
                if len(queue)>=cap:
                    raise ApiError(429,'RATE_LIMITED','Quá nhiều yêu cầu.',{'Retry-After':str(max(1,int(60-(now-queue[0]))+1))})
            for label, _ in limits: self.rate[label].append(now)
            # Remove old identities so transient UUIDs cannot grow memory forever.
            if len(self.rate)>5000:
                for label in list(self.rate):
                    if not self.rate[label] or now-self.rate[label][-1]>=60: del self.rate[label]

class Handler(BaseHTTPRequestHandler):
    server_version = 'TvDashboardConfig/1.0'
    def log_message(self, fmt, *args):
        # Do not log query strings, keys, Authorization, payloads or URLs.
        pass

    def send_json(self, status, body, headers=None):
        self.send_bytes(status,json.dumps(body,ensure_ascii=False).encode(),'application/json; charset=utf-8',headers)

    def send_bytes(self,status,body,content_type,headers=None):
        self.send_response(status)
        self.send_header('Content-Type',content_type)
        self.send_header('Content-Length',str(len(body)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        for k,v in (headers or {}).items():
            for item in (v if isinstance(v,list) else [v]): self.send_header(k,item)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self): self.dispatch()
    def do_PUT(self): self.dispatch()
    def do_POST(self): self.dispatch()
    def do_DELETE(self): self.dispatch()

    def dispatch(self):
        self.request_id = str(uuid.uuid4())
        self._request_body_consumed = False
        self.connection.settimeout(15)
        try:
            u=urlsplit(self.path)
            self.path_only=u.path
            self.query=parse_qs(u.query,keep_blank_values=True,max_num_fields=20)
            self.route()
        except ApiError as e:
            self._discard_small_request_body()
            self.send_json(e.status,{'error':{'code':e.code,'message':e.message,'requestId':self.request_id}},e.headers)
        except (ValueError, UnicodeError):
            self._discard_small_request_body()
            self.send_json(400,{'error':{'code':'INVALID_ARGUMENT','message':'Yêu cầu không hợp lệ.','requestId':self.request_id}})
        except (BrokenPipeError,ConnectionResetError,TimeoutError): pass
        except Exception:
            logging.exception('Request failed requestId=%s',self.request_id)
            self._discard_small_request_body()
            self.send_json(500,{'error':{'code':'INTERNAL_ERROR','message':'Lỗi server.','requestId':self.request_id}})

    def _discard_small_request_body(self):
        if self._request_body_consumed or self.headers.get('Transfer-Encoding'):
            return
        values=self.headers.get_all('Content-Length') or []
        if len(values)!=1:
            return
        try:length=int(values[0])
        except ValueError:return
        if not 0<length<=16384:
            return
        try:
            self.rfile.read(length)
            self._request_body_consumed=True
        except (OSError,TimeoutError):
            self.close_connection=True

    def param(self,name,required=True,default=None):
        values=self.query.get(name,[])
        if len(values)>1 or (required and (not values or not values[0])):
            raise ApiError(400,'INVALID_ARGUMENT','Tham số '+name+' không hợp lệ.')
        return values[0] if values else default

    def department(self,value):
        if not isinstance(value,str) or not ID_PATTERN.fullmatch(value):
            raise ApiError(400,'INVALID_ARGUMENT','Mã phòng ban không hợp lệ.')
        with self.server.connect() as c:
            row=c.execute('SELECT * FROM departments WHERE id=? AND active=1',(value,)).fetchone()
        if row is None: raise ApiError(404,'DEPARTMENT_NOT_FOUND','Phòng ban không tồn tại.')
        return dict(row)

    def tv_auth(self):
        self.server.limit('client:'+self.client_address[0])
        values=self.headers.get_all('x-api-key') or []
        if len(values)!=1 or not values[0]:
            self.server.limit('invalid:'+self.client_address[0])
            raise ApiError(401,'INVALID_API_KEY','Key không hợp lệ.')
        with self.server.connect() as c:
            row=c.execute('SELECT * FROM api_keys WHERE key_hash=? AND active=1',(digest(values[0]),)).fetchone()
        if row is None:
            self.server.limit('invalid:'+self.client_address[0])
            raise ApiError(401,'INVALID_API_KEY','Key không hợp lệ.')
        return dict(row)

    def scope(self,key,dept):
        allowed=json.loads(key['scope_json']) if key['scope_json'] is not None else None
        if allowed is not None and dept not in allowed:
            raise ApiError(403,'FORBIDDEN_DEPARTMENT','Key không có quyền đọc phòng ban này.')

    def admin_auth(self):
        values=self.headers.get_all('Authorization') or []
        if not values and self.headers.get('Cookie'):
            from admin_portal import AdminPortal
            return AdminPortal(self).session(self.command in ('POST','PUT','DELETE'))['username']
        if len(values)!=1 or not values[0].startswith('Bearer '):
            raise ApiError(401,'UNAUTHENTICATED','Cần token quản trị.')
        if not hmac.compare_digest(digest(values[0][7:]),self.server.admin_hash):
            raise ApiError(403,'FORBIDDEN','Không có quyền admin.')
        return 'admin-token'

    def body(self):
        if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length') or [])!=1:
            raise ApiError(400,'INVALID_ARGUMENT','Cần Content-Length hợp lệ.')
        try: length=int(self.headers['Content-Length'])
        except (TypeError,ValueError): raise ApiError(400,'INVALID_ARGUMENT','Content-Length không hợp lệ.')
        if not 0<length<=16384: raise ApiError(400,'INVALID_ARGUMENT','Body tối đa 16 KiB.')
        if self.headers.get('Content-Type','').split(';')[0].strip()!='application/json':
            raise ApiError(400,'INVALID_ARGUMENT','Cần Content-Type application/json.')
        def pairs(items):
            d={}
            for k,v in items:
                if k in d: raise ValueError('duplicate key')
                d[k]=v
            return d
        try:
            raw=self.rfile.read(length)
            self._request_body_consumed=True
            data=json.loads(raw.decode('utf-8'),object_pairs_hook=pairs)
        except (ValueError,UnicodeError): raise ApiError(400,'INVALID_ARGUMENT','JSON không hợp lệ.')
        if not isinstance(data,dict): raise ApiError(400,'INVALID_ARGUMENT','Body phải là object.')
        return data

    def route(self):
        path,method=self.path_only,self.command
        if path=='/api/v1/cloud/enroll' and method=='POST':
            if not self.server.config.get('publicOrigin'):
                raise ApiError(404,'NOT_FOUND','Ghép cloud chưa bật.')
            if self.headers.get('Origin'):
                raise ApiError(403,'DEVICE_ONLY','Chỉ TV được đổi mã ghép.')
            self.server.limit('cloud-enroll:'+self.client_address[0])
            from cloud_pairing import redeem
            return self.send_json(200,redeem(self.server,self.body(),self.headers.get('x-api-key','')))
        from remote_logs import handle as handle_diagnostics
        if handle_diagnostics(self): return
        policy_match=re.fullmatch(r'/api/v1/devices/([0-9a-f-]{36})/policy',path)
        if policy_match and method=='GET':
            device=policy_match.group(1);key=self.tv_auth()
            try:
                uid=uuid.UUID(device)
                if uid.version!=4 or str(uid)!=device:raise ValueError()
            except ValueError:raise ApiError(400,'INVALID_ARGUMENT','Device ID không hợp lệ.')
            self.server.limit(key['key_id'],device)
            with self.server.connect() as c:
                tv=c.execute('SELECT department_id,last_key_id FROM devices WHERE device_id=?',(device,)).fetchone()
                if not tv:raise ApiError(404,'DEVICE_NOT_FOUND','TV chưa đăng ký; hoàn tất ghép API trước.')
                owner=c.execute('SELECT key_id FROM device_bindings WHERE device_id=?',(device,)).fetchone()
                if (owner['key_id'] if owner else tv['last_key_id'])!=key['key_id']:
                    raise ApiError(403,'DEVICE_KEY_MISMATCH','Key không thuộc TV.')
                self.scope(key,tv['department_id'])
                pin=c.execute('SELECT policy_json,version FROM device_pins WHERE device_id=?',(device,)).fetchone()
                diagnostic=c.execute('SELECT enabled,version FROM diagnostic_policy WHERE device_id=?',(device,)).fetchone()
            return self.send_json(200,{'databaseEpoch':self.server.database_epoch,'deviceId':device,
                'phonePinPolicy':json.loads(pin['policy_json']) if pin else {'mode':'random'},
                'phonePinPolicyVersion':pin['version'] if pin else 0,
                'diagnosticsEnabled':bool(diagnostic['enabled']) if diagnostic else False,
                'diagnosticsPolicyVersion':diagnostic['version'] if diagnostic else 0})
        pin_ack=re.fullmatch(r'/api/v1/devices/([0-9a-f-]{36})/phone-pin/ack',path)
        if pin_ack and method=='POST':
            device=pin_ack.group(1);key=self.tv_auth();data=self.body()
            self.server.limit(key['key_id'],device)
            try:
                if str(uuid.UUID(device))!=device:raise ValueError()
            except ValueError:raise ApiError(400,'INVALID_ARGUMENT','Device ID không hợp lệ.')
            if set(data)!={'version'} or type(data['version']) is not int or data['version']<0:
                raise ApiError(400,'INVALID_ARGUMENT','Phiên bản PIN không hợp lệ.')
            with self.server.connect() as c:
                c.execute('BEGIN IMMEDIATE')
                tv=c.execute('SELECT department_id,last_key_id FROM devices WHERE device_id=?',(device,)).fetchone()
                if not tv:raise ApiError(404,'DEVICE_NOT_FOUND','TV chưa đăng ký.')
                owner=c.execute('SELECT key_id FROM device_bindings WHERE device_id=?',(device,)).fetchone()
                if (owner['key_id'] if owner else tv['last_key_id'])!=key['key_id']:
                    raise ApiError(403,'DEVICE_KEY_MISMATCH','Key không thuộc TV.')
                self.scope(key,tv['department_id'])
                current=c.execute('SELECT version FROM device_pins WHERE device_id=?',(device,)).fetchone()
                current_version=current['version'] if current else 0
                if data['version']!=current_version:raise ApiError(409,'POLICY_CHANGED','PIN đã thay đổi; TV cần lấy lại cấu hình.')
                c.execute('INSERT OR REPLACE INTO device_pin_ack VALUES(?,?,?)',(device,current_version,utcnow()))
            return self.send_json(200,{'acknowledged':True,'version':current_version})
        if path=='/api/v1/pairing/enroll' and method=='POST':
            from lan_pairing import handle_enrollment
            return handle_enrollment(self)
        if path=='/admin' or path.startswith('/admin/'):
            from admin_portal import AdminPortal
            return AdminPortal(self).handle()
        if path in ('/tv-demo', '/tv-demo/') and method=='GET':
            # Public mock-only TV page. Never inject credentials or operational data.
            return self.send_bytes(200, (ROOT/'dashboard/tv-dashboard.html').read_bytes(), 'text/html; charset=utf-8')
        if path=='/health' and method=='GET':
            return self.send_json(200,{'status':'ok','mode':'config-service','serverTime':utcnow()})
        if path.startswith('/sample-dashboard/') and method=='GET':
            dept=self.department(unquote(path[len('/sample-dashboard/'):]))
            page='''<!doctype html><html lang="vi"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Dashboard mẫu</title><style>body{background:#101827;color:#eef2ff;font:24px sans-serif;padding:5vw}small{color:#9ca3af}h1{color:#60a5fa}</style><small>TV DASHBOARD — DỮ LIỆU MẪU</small><h1>'''+escape(dept['name'])+'</h1><p>'+escape(dept['description'])+'</p><p>Mã: '+escape(dept['id'])+'</p><p>'+utcnow()+'</p></html>'
            return self.send_bytes(200,page.encode(),'text/html; charset=utf-8')
        if path=='/api/v1/settings/events' and method=='GET':
            from settings_events import stream_settings_events
            return stream_settings_events(self)
        if path=='/api/v1/departments' and method=='GET':
            key=self.tv_auth();self.server.limit(key['key_id'])
            allowed=json.loads(key['scope_json']) if key['scope_json'] else None
            with self.server.connect() as c:
                rows=c.execute('SELECT * FROM departments WHERE active=1 ORDER BY rowid').fetchall()
            result=[{'id':r['id'],'name':r['name'],'parentId':r['parent_id'],'kind':r['kind']} for r in rows if allowed is None or r['id'] in allowed]
            return self.send_json(200,{'departments':result})
        if path=='/api/v1/settings' and method=='GET':
            key=self.tv_auth()
            dep=self.param('department');device=self.param('deviceId');name=self.param('name').strip()
            app=self.param('appVersion',False,None)
            try:
                uid=uuid.UUID(device)
                if uid.version!=4 or str(uid)!=device.lower(): raise ValueError()
            except ValueError: raise ApiError(400,'INVALID_ARGUMENT','deviceId phải là UUID v4.')
            device=str(uid)
            if not 1<=len(name)<=64 or any(ord(c)<32 or 127<=ord(c)<160 for c in name):
                raise ApiError(400,'INVALID_ARGUMENT','Tên TV cần 1–64 ký tự, không có ký tự điều khiển.')
            if app is not None and (len(app)>32 or any(ord(c)<32 for c in app)):
                raise ApiError(400,'INVALID_ARGUMENT','appVersion không hợp lệ.')
            # Validate syntax before authorization; valid unknown departments return 404.
            if not ID_PATTERN.fullmatch(dep): raise ApiError(400,'INVALID_ARGUMENT','Mã phòng ban không hợp lệ.')
            self.scope(key,dep);dept=self.department(dep)
            self.server.limit(key['key_id'],device)
            load_state=self.param('loadState',False,'unknown')
            load_hash=self.param('loadUrlHash',False,'')
            if load_state not in ('unknown','loading','loaded','error') or (load_hash and not re.fullmatch('[0-9a-f]{64}',load_hash)):
                raise ApiError(400,'INVALID_ARGUMENT','Trạng thái tải không hợp lệ.')
            now=utcnow()
            with self.server.connect() as c:
                c.execute('BEGIN IMMEDIATE')
                owner=c.execute('SELECT key_id FROM device_bindings WHERE device_id=?',(device,)).fetchone()
                legacy=c.execute('SELECT last_key_id FROM devices WHERE device_id=?',(device,)).fetchone()
                bound=owner['key_id'] if owner else (legacy['last_key_id'] if legacy else None)
                if bound and bound!=key['key_id']:raise ApiError(403,'DEVICE_KEY_MISMATCH','Key không thuộc TV này; cần admin cấp lại key.')
                other=c.execute('SELECT device_id FROM device_bindings WHERE key_id=?',(key['key_id'],)).fetchone()
                if key['key_id'].startswith(('lan-', 'cloud-tv-')) and other and other['device_id']!=device:raise ApiError(403,'DEVICE_KEY_MISMATCH','Key chỉ dùng cho TV đã ghép.')
                c.execute('INSERT OR IGNORE INTO device_bindings VALUES(?,?)',(device,key['key_id']))
                row=c.execute('SELECT * FROM settings WHERE department_id=?',(dep,)).fetchone()
                if row is None: raise ApiError(409,'SETTINGS_NOT_CONFIGURED','Phòng ban chưa có URL.')
                try: validate_url(row['dashboard_url'],self.server.config.get('developmentHttp',False))
                except ApiError: raise ApiError(409,'SETTINGS_URL_INVALID','URL đã lưu không phù hợp chế độ hiện tại; admin cần cập nhật URL HTTPS.')
                c.execute('''INSERT INTO devices VALUES(?,?,?,?,?,?,?,NULL)
                ON CONFLICT(device_id) DO UPDATE SET name=excluded.name,department_id=excluded.department_id,
                last_seen_at=excluded.last_seen_at,app_version=excluded.app_version,last_key_id=excluded.last_key_id,archived_at=NULL''',
                (device,name,dep,now,now,app,key['key_id']))
                expected_hash=hashlib.sha256(row['dashboard_url'].encode()).hexdigest()
                if load_hash==expected_hash:
                    c.execute('INSERT OR REPLACE INTO device_load_status VALUES(?,?,?,?)',(device,load_state,now,load_hash))
                from remote_logs import enabled as diagnostics_enabled
                diagnostics=diagnostics_enabled(c,device)
                pin_row=c.execute('SELECT policy_json FROM device_pins WHERE device_id=?',(device,)).fetchone()
                pin_policy=json.loads(pin_row['policy_json']) if pin_row else {'mode':'random'}
                pin_version=c.execute('SELECT version FROM device_pins WHERE device_id=?',(device,)).fetchone()
                pin_version=pin_version['version'] if pin_version else 0
                diagnostics_version=c.execute('SELECT version FROM diagnostic_policy WHERE device_id=?',(device,)).fetchone()
                diagnostics_version=diagnostics_version['version'] if diagnostics_version else 0
            return self.send_json(200,{'databaseEpoch':self.server.database_epoch,'diagnosticsEnabled':diagnostics,'phonePinPolicy':pin_policy,'department':{'id':dep,'name':dept['name']},'dashboardUrl':row['dashboard_url'],
                'version':row['version'],'pollSeconds':self.server.config['pollSeconds'],'serverTime':now,
                'diagnosticsPolicyVersion':diagnostics_version,'phonePinPolicyVersion':pin_version})
        match=re.fullmatch(r'/api/v1/departments/([^/]+)/settings',path)
        if match and method=='PUT':
            actor=self.admin_auth();dep=unquote(match.group(1));dept=self.department(dep);data=self.body()
            url=validate_url(data.get('dashboardUrl'), self.server.config.get('developmentHttp',False))
            expected=data.get('expectedVersion')
            if type(expected) is not int or expected<0: raise ApiError(400,'INVALID_ARGUMENT','expectedVersion phải là số nguyên không âm.')
            now=utcnow()
            with self.server.connect() as c:
                c.execute('BEGIN IMMEDIATE')
                old=c.execute('SELECT * FROM settings WHERE department_id=?',(dep,)).fetchone()
                current=old['version'] if old else 0
                if expected!=current: raise ApiError(409,'VERSION_CONFLICT','Version đã thay đổi; lấy lại cấu hình.')
                version=current
                if old is None or old['dashboard_url']!=url:
                    version=current+1
                    c.execute('INSERT INTO settings VALUES(?,?,?,?,?) ON CONFLICT(department_id) DO UPDATE SET dashboard_url=excluded.dashboard_url,version=excluded.version,updated_at=excluded.updated_at,updated_by=excluded.updated_by',(dep,url,version,now,actor))
                    c.execute('INSERT INTO config_audit(department_id,old_url,new_url,old_version,new_version,changed_by,changed_at,request_id) VALUES(?,?,?,?,?,?,?,?)',
                    (dep,old['dashboard_url'] if old else None,url,current,version,actor,now,self.request_id))
                else: now=old['updated_at']
            if version!=current:self.server.publish_settings(dep)
            return self.send_json(200,{'department':{'id':dep,'name':dept['name']},'dashboardUrl':url,'version':version,'updatedAt':now})
        if path=='/api/v1/devices' and method=='GET':
            self.admin_auth();dep=self.param('department',False,None)
            if dep is not None:self.department(dep)
            status=self.param('status',False,None)
            if status is not None and status not in ('online','offline'):
                raise ApiError(400,'INVALID_ARGUMENT','Trạng thái cần online hoặc offline.')
            search=self.param('q',False,'').strip()
            if len(search)>64 or any(ord(ch)<32 or 127<=ord(ch)<160 for ch in search):
                raise ApiError(400,'INVALID_ARGUMENT','Tìm tên TV tối đa 64 ký tự.')
            archived=self.param('archived',False,'false')
            if archived not in ('true','false'):raise ApiError(400,'INVALID_ARGUMENT','archived cần true hoặc false.')
            try:
                page=int(self.param('page',False,'1'));size=int(self.param('pageSize',False,'20'))
                if page<1 or not 1<=size<=100:raise ValueError()
            except ValueError:raise ApiError(400,'INVALID_ARGUMENT','Phân trang không hợp lệ.')
            now=datetime.now(timezone.utc).replace(microsecond=0)
            stamp=now.isoformat().replace('+00:00','Z')
            cutoff=(now-timedelta(days=30)).isoformat().replace('+00:00','Z')
            online_cutoff=(now-timedelta(seconds=self.server.config['offlineSeconds'])).isoformat().replace('+00:00','Z')
            conditions=['d.archived_at IS '+('NOT NULL' if archived=='true' else 'NULL')]
            args=[]
            if dep:conditions.append('d.department_id=?');args.append(dep)
            if search:
                # Literal substring: % and _ from a TV name are not SQL wildcard patterns.
                conditions.append('(instr(lower(d.name),lower(?))>0 OR instr(lower(d.device_id),lower(?))>0)');args.extend([search,search])
            query=' FROM devices d JOIN departments p ON p.id=d.department_id WHERE '+' AND '.join(conditions)
            with self.server.connect() as c:
                c.execute('BEGIN IMMEDIATE')
                c.execute('UPDATE devices SET archived_at=? WHERE last_seen_at<? AND archived_at IS NULL',(stamp,cutoff))
                summary=c.execute('SELECT COUNT(*) AS total,SUM(CASE WHEN d.last_seen_at>=? THEN 1 ELSE 0 END) AS online'+query,[online_cutoff,*args]).fetchone()
                filtered=query
                if status:
                    filtered+=' AND d.last_seen_at'+('>=?' if status=='online' else '<?');args.append(online_cutoff)
                total=c.execute('SELECT COUNT(*)'+filtered,args).fetchone()[0]
                rows=c.execute('SELECT d.*,p.name AS department_name'+filtered+' ORDER BY d.last_seen_at DESC,d.device_id LIMIT ? OFFSET ?',[*args,size,(page-1)*size]).fetchall()
                loads={r['device_id']:{'state':r['state'],'observed_at':r['observed_at']} for r in c.execute('SELECT l.*,s.dashboard_url AS current_url FROM device_load_status l JOIN devices d ON d.device_id=l.device_id JOIN settings s ON s.department_id=d.department_id') if r['device_id'] in {d['device_id'] for d in rows} and r['url_hash']==hashlib.sha256(r['current_url'].encode()).hexdigest()}
            items=[{'hasCloudDeviceKey':r['last_key_id']=='cloud-tv-'+r['device_id'],'dashboardLoad':loads.get(r['device_id']), 'deviceId':r['device_id'],'name':r['name'],'department':{'id':r['department_id'],'name':r['department_name']},
                'appVersion':r['app_version'],'firstSeenAt':r['first_seen_at'],'lastSeenAt':r['last_seen_at'],'archivedAt':r['archived_at'],
                'status':'online' if r['last_seen_at']>=online_cutoff else 'offline'} for r in rows]
            online=summary['online'] or 0
            return self.send_json(200,{'items':items,'page':page,'pageSize':size,'total':total,'serverTime':stamp,
                'summary':{'total':summary['total'],'online':online,'offline':summary['total']-online},
                'pollSeconds':self.server.config['pollSeconds'],'offlineSeconds':self.server.config['offlineSeconds'],'archiveAfterDays':30})
        if path=='/api/v1/audit' and method=='GET':
            self.admin_auth()
            with self.server.connect() as c: rows=c.execute('SELECT * FROM config_audit ORDER BY id DESC LIMIT 100').fetchall()
            return self.send_json(200,{'items':[dict(r) for r in rows]})
        raise ApiError(404,'NOT_FOUND','Endpoint không tồn tại.')

# Launch through backend/run_service.py to resolve one shared connection.json.
