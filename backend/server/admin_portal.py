"""Local admin portal: password hashes, expiring sessions and same-origin CSRF checks."""
from collections import deque
import hashlib
import hmac
from http.cookies import SimpleCookie, CookieError
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import time
from urllib.parse import urlsplit

WEB = Path(__file__).resolve().parents[1]/'admin'
ITERATIONS = 600_000
SESSION_SECONDS = 8*3600

def password_hash(password,salt):
    return hashlib.pbkdf2_hmac('sha256',password.encode(),bytes.fromhex(salt),ITERATIONS).hex()

def init_admin(server):
    server.login_attempts={}
    server.login_csrf_secret=secrets.token_bytes(32)
    with server.connect() as db:
        db.executescript('''
        CREATE TABLE IF NOT EXISTS admin_users (
          username TEXT PRIMARY KEY, salt TEXT NOT NULL, password_hash TEXT NOT NULL,
          iterations INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1);
        CREATE TABLE IF NOT EXISTS admin_sessions (
          token_hash TEXT PRIMARY KEY, username TEXT NOT NULL REFERENCES admin_users(username),
          csrf_token TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS idx_admin_session_expiry ON admin_sessions(expires_at);
        ''')

def provision_admin(server,path):
    """Generate credentials only once. Existing DB account/password always stays authoritative."""
    path=Path(path)
    with server.connect() as db:
        if db.execute('SELECT 1 FROM admin_users LIMIT 1').fetchone():return False
    if not path.exists():
        path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('x',encoding='utf-8') as f:
            json.dump({'username':'admin','password':secrets.token_urlsafe(24)},f,indent=2)
        if os.name!='nt':path.chmod(0o600)
    data=json.loads(path.read_text())
    user=data.get('username');password=data.get('password')
    if not isinstance(user,str) or not re.fullmatch(r'[a-z0-9._-]{1,64}',user) or not isinstance(password,str) or not 12<=len(password)<=256:
        raise ValueError('admin-bootstrap.json cần username hợp lệ và mật khẩu 12–256 ký tự.')
    salt=secrets.token_hex(16)
    with server.connect() as db:
        db.execute('INSERT OR IGNORE INTO admin_users VALUES(?,?,?,?,1)',(user,salt,password_hash(password,salt),ITERATIONS))
    return True

class AdminPortal:
    def __init__(self,handler):self.h=handler;self.server=handler.server
    def error(self,status,code,message,headers=None):
        from config_service import ApiError
        raise ApiError(status,code,message,headers)
    def cookie(self,name,value,max_age):
        secure='; Secure' if (self.server.config.get('publicOrigin') or isinstance(self.h.connection,ssl.SSLSocket)) else ''
        return f'{name}={value}; Path=/; HttpOnly; SameSite=Strict; Max-Age={max_age}'+secure
    def cookies(self):
        raw=self.h.headers.get_all('Cookie') or []
        if len(raw)>1:return {}
        jar=SimpleCookie()
        try:jar.load(raw[0] if raw else '')
        except CookieError:return {}
        return {key:m.value for key,m in jar.items()}
    def same_origin(self):
        values=self.h.headers.get_all('Origin') or []
        hosts=self.h.headers.get_all('Host') or []
        public=self.server.config.get('publicOrigin')
        expected=public or ('https' if isinstance(self.h.connection,ssl.SSLSocket) else 'http')+'://'+(hosts[0] if hosts else '')
        if len(values)!=1 or len(hosts)!=1 or values[0]!=expected or expected.rsplit('://',1)[-1]!=hosts[0]:
            self.error(403,'CSRF_FAILED','Yêu cầu phải đến từ chính trang quản trị.')
    def session(self,mutation=False):
        token=self.cookies().get('tv_admin_session','')
        if not re.fullmatch(r'[A-Za-z0-9_-]{43}',token):self.error(401,'UNAUTHENTICATED','Phiên đăng nhập hết hạn hoặc chưa đăng nhập.')
        now=int(time.time())
        with self.server.connect() as db:
            db.execute('DELETE FROM admin_sessions WHERE expires_at<=?',(now,))
            row=db.execute('SELECT s.*,u.active FROM admin_sessions s JOIN admin_users u ON u.username=s.username WHERE token_hash=? AND expires_at>?',(hashlib.sha256(token.encode()).hexdigest(),now)).fetchone()
        if not row or not row['active']:self.error(401,'UNAUTHENTICATED','Phiên đăng nhập hết hạn hoặc chưa đăng nhập.')
        if mutation:
            self.same_origin()
            supplied=self.h.headers.get_all('X-CSRF-Token') or []
            if len(supplied)!=1 or not re.fullmatch(r'[A-Za-z0-9_-]{43}',supplied[0]) or not hmac.compare_digest(supplied[0],row['csrf_token']):self.error(403,'CSRF_FAILED','Token bảo vệ phiên không hợp lệ. Tải lại trang rồi thử lại.')
        return dict(row)
    def login_token(self):
        raw=str(int(time.time()))+'.'+secrets.token_hex(16)
        sig=hmac.new(self.server.login_csrf_secret,raw.encode(),'sha256').hexdigest()
        return raw+'.'+sig
    def check_login_csrf(self):
        self.same_origin()
        cookie=self.cookies().get('tv_admin_login','')
        supplied=self.h.headers.get_all('X-CSRF-Token') or []
        try:
            issued,nonce,sig=cookie.split('.')
            if not re.fullmatch('[0-9a-f]{32}',nonce) or not 0<=time.time()-int(issued)<=600:raise ValueError()
            expected=hmac.new(self.server.login_csrf_secret,(issued+'.'+nonce).encode(),'sha256').hexdigest()
            if len(supplied)!=1 or not hmac.compare_digest(cookie,supplied[0]) or not hmac.compare_digest(expected,sig):raise ValueError()
        except (ValueError,TypeError):self.error(403,'CSRF_FAILED','Trang đăng nhập hết hạn. Tải lại trang rồi thử lại.')
    def login_limit(self):
        now=time.monotonic();ip=self.h.client_address[0]
        with self.server.lock:
            for k in list(self.server.login_attempts):
                q=self.server.login_attempts[k]
                while q and now-q[0]>=60:q.popleft()
                if not q:del self.server.login_attempts[k]
            if ip not in self.server.login_attempts and len(self.server.login_attempts)>=1000:self.error(429,'RATE_LIMITED','Thử lại sau một phút.',{'Retry-After':'60'})
            q=self.server.login_attempts.setdefault(ip,deque())
            if len(q)>=5:self.error(429,'RATE_LIMITED','Quá nhiều lần đăng nhập. Thử lại sau một phút.',{'Retry-After':str(max(1,int(60-(now-q[0]))+1))})
            q.append(now)
    def login(self):
        self.check_login_csrf();self.login_limit();body=self.h.body()
        user=body.get('username');password=body.get('password')
        if not isinstance(user,str) or not re.fullmatch('[a-z0-9._-]{1,64}',user) or not isinstance(password,str) or not 1<=len(password)<=256:self.error(401,'INVALID_CREDENTIALS','Tên đăng nhập hoặc mật khẩu không đúng.')
        with self.server.connect() as db:row=db.execute('SELECT * FROM admin_users WHERE username=?',(user,)).fetchone()
        salt=row['salt'] if row else '00'*16
        hashed=password_hash(password,salt)
        if not row or not row['active'] or not hmac.compare_digest(hashed,row['password_hash']):self.error(401,'INVALID_CREDENTIALS','Tên đăng nhập hoặc mật khẩu không đúng.')
        token=secrets.token_urlsafe(32);csrf=secrets.token_urlsafe(32);now=int(time.time())
        old=self.cookies().get('tv_admin_session','')
        with self.server.connect() as db:
            db.execute('DELETE FROM admin_sessions WHERE expires_at<=? OR token_hash=?',(now,hashlib.sha256(old.encode()).hexdigest()))
            db.execute('INSERT INTO admin_sessions VALUES(?,?,?,?,?)',(hashlib.sha256(token.encode()).hexdigest(),user,csrf,now,now+SESSION_SECONDS))
        self.h.send_json(200,{'authenticated':True,'username':user,'csrfToken':csrf,'expiresAt':now+SESSION_SECONDS},
                         {'Set-Cookie':[self.cookie('tv_admin_session',token,SESSION_SECONDS),self.cookie('tv_admin_login','',0)]})
    def handle(self):
        path=self.h.path_only;method=self.h.command
        revoke=re.fullmatch(r'/admin/api/devices/([0-9a-f-]{36})/revoke-cloud',path)
        if revoke and method=='POST':
            self.session(True)
            if not self.server.config.get('publicOrigin'):self.error(404,'NOT_FOUND','Chỉ dùng trên cloud.')
            device=revoke.group(1)
            with self.server.connect() as db:
                db.execute('BEGIN IMMEDIATE')
                row=db.execute('SELECT key_id FROM device_bindings WHERE device_id=?',(device,)).fetchone()
                if not row or row[0]!='cloud-tv-'+device:self.error(409,'NOT_DEVICE_KEY','TV này chưa dùng khóa cloud riêng.')
                db.execute('UPDATE api_keys SET active=0 WHERE key_id=?',(row[0],))
                db.execute('DELETE FROM cloud_invites WHERE used_device=?',(device,))
            return self.h.send_json(200,{'revoked':True})
        if path=='/admin/api/cloud-codes' and method=='POST':
            self.session(True)
            if not self.server.config.get('publicOrigin'):
                self.error(404,'NOT_FOUND','Ghép cloud chưa bật.')
            dep=self.h.department(self.h.body().get('department'))
            from cloud_pairing import issue
            return self.h.send_json(201,issue(self.server,dep['id']))
        assets={'/admin':('index.html','text/html; charset=utf-8'),'/admin/':('index.html','text/html; charset=utf-8'),'/admin/app.js':('app.js','text/javascript; charset=utf-8'),'/admin/style.css':('style.css','text/css; charset=utf-8')}
        if path in assets and method=='GET':
            name,mime=assets[path]
            return self.h.send_bytes(200,(WEB/name).read_bytes(),mime,{'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",'X-Frame-Options':'DENY','Referrer-Policy':'no-referrer'})
        if path=='/admin/api/session' and method=='GET':
            from config_service import ApiError
            try:
                row=self.session()
                return self.h.send_json(200,{'authenticated':True,'username':row['username'],'csrfToken':row['csrf_token'],'expiresAt':row['expires_at'],'cloudPairing':bool(self.server.config.get('publicOrigin')),'developmentHttp':self.server.config.get('developmentHttp',False)})
            except ApiError as e:
                if e.status!=401:raise
            token=self.login_token()
            return self.h.send_json(200,{'authenticated':False,'csrfToken':token},{'Set-Cookie':self.cookie('tv_admin_login',token,600)})
        if path=='/admin/api/login' and method=='POST':return self.login()
        if path=='/admin/api/logout' and method=='POST':
            row=self.session(True)
            with self.server.connect() as db:db.execute('DELETE FROM admin_sessions WHERE token_hash=?',(row['token_hash'],))
            return self.h.send_json(200,{'ok':True},{'Set-Cookie':self.cookie('tv_admin_session','',0)})
        if path=='/admin/api/password' and method=='POST':
            row=self.session(True);self.login_limit();body=self.h.body();old=body.get('currentPassword');new=body.get('newPassword')
            if not isinstance(old,str) or not 1<=len(old)<=256 or not isinstance(new,str) or not 12<=len(new)<=256:self.error(400,'INVALID_PASSWORD','Mật khẩu mới cần 12–256 ký tự.')
            with self.server.connect() as db:
                user=db.execute('SELECT * FROM admin_users WHERE username=?',(row['username'],)).fetchone()
                if not hmac.compare_digest(password_hash(old,user['salt']),user['password_hash']):self.error(401,'INVALID_CREDENTIALS','Mật khẩu hiện tại không đúng.')
                if old==new:self.error(400,'INVALID_PASSWORD','Mật khẩu mới cần khác mật khẩu hiện tại.')
                salt=secrets.token_hex(16)
                db.execute('UPDATE admin_users SET salt=?,password_hash=?,iterations=? WHERE username=?',(salt,password_hash(new,salt),ITERATIONS,row['username']))
                db.execute('DELETE FROM admin_sessions WHERE username=?',(row['username'],))
            return self.h.send_json(200,{'ok':True},{'Set-Cookie':self.cookie('tv_admin_session','',0)})
        pin_match=re.fullmatch(r'/admin/api/devices/([a-f0-9-]{36})/phone-pin',path)
        if pin_match and method in ('GET','PUT'):
            actor=self.session(method=='PUT');device=pin_match.group(1)
            with self.server.connect() as db:
                if method=='PUT':db.execute('BEGIN IMMEDIATE')
                tv=db.execute('SELECT department_id FROM devices WHERE device_id=?',(device,)).fetchone()
                if not tv:self.error(404,'DEVICE_NOT_FOUND','TV chưa đăng ký với server.')
                if method=='GET':
                    row=db.execute('SELECT policy_json,updated_at,version FROM device_pins WHERE device_id=?',(device,)).fetchone()
                    version=row['version'] if row else 0
                    ack=db.execute('SELECT policy_version,received_at FROM device_pin_ack WHERE device_id=?',(device,)).fetchone()
                    return self.h.send_json(200,{'mode':json.loads(row['policy_json'])['mode'] if row else 'random','updatedAt':row['updated_at'] if row else None,'version':version,'tvApplied':bool(ack and ack['policy_version']==version),'appliedAt':ack['received_at'] if ack and ack['policy_version']==version else None})
                body=self.h.body();mode=body.get('mode')
                if mode=='custom':
                    pin=body.get('pin')
                    if not isinstance(pin,str) or not re.fullmatch(r'[0-9]{6,8}',pin):self.error(400,'INVALID_PIN','PIN phải gồm 6–8 chữ số.')
                    salt=secrets.token_hex(16)
                    policy={'mode':'custom','salt':salt,'hash':hashlib.pbkdf2_hmac('sha256',pin.encode(),bytes.fromhex(salt),100000).hex(),'iterations':100000}
                elif mode=='random':policy={'mode':'random'}
                else:self.error(400,'INVALID_PIN_MODE','Chọn PIN ngẫu nhiên hoặc tùy chỉnh.')
                from config_service import utcnow
                old=db.execute('SELECT version FROM device_pins WHERE device_id=?',(device,)).fetchone()
                version=(old['version'] if old else 0)+1
                db.execute('''INSERT INTO device_pins(device_id,policy_json,updated_by,updated_at,version)
                    VALUES(?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET policy_json=excluded.policy_json,
                    updated_by=excluded.updated_by,updated_at=excluded.updated_at,version=excluded.version''',
                    (device,json.dumps(policy),actor['username'],utcnow(),version))
            self.server.publish_settings(tv['department_id'])
            return self.h.send_json(200,{'ok':True,'mode':mode,'version':version,'message':'Đã lưu trên server. TV chỉ áp dụng sau khi xác nhận nhận cấu hình qua SSE/API.'})
        if path=='/admin/api/departments' and method=='GET':
            self.session()
            with self.server.connect() as db:
                rows=db.execute('SELECT d.*,s.dashboard_url,s.version,s.updated_at,s.updated_by FROM departments d LEFT JOIN settings s ON s.department_id=d.id WHERE d.active=1 ORDER BY d.rowid').fetchall()
            return self.h.send_json(200,{'items':[{'id':r['id'],'name':r['name'],'description':r['description'],'dashboardUrl':r['dashboard_url'],'version':r['version'] or 0,'updatedAt':r['updated_at'],'updatedBy':r['updated_by']} for r in rows]})
        if path=='/admin/api/validate-url' and method=='POST':
            self.session(True)
            from config_service import validate_url
            url=validate_url(self.h.body().get('dashboardUrl'),self.server.config.get('developmentHttp',False))
            return self.h.send_json(200,{'valid':True,'dashboardUrl':url,'message':'Định dạng URL hợp lệ. Chưa kiểm tra mạng hoặc đăng nhập dashboard.'})
        if path=='/admin/api/audit' and method=='GET':
            self.session();dep=self.h.param('department',False,None)
            if dep:self.h.department(dep)
            try:
                page=int(self.h.param('page',False,'1'));size=int(self.h.param('pageSize',False,'20'))
                if page<1 or not 1<=size<=100:raise ValueError()
            except ValueError:self.error(400,'INVALID_ARGUMENT','Phân trang không hợp lệ.')
            query=' FROM config_audit a LEFT JOIN departments d ON d.id=a.department_id';args=[]
            if dep:query+=' WHERE a.department_id=?';args=[dep]
            with self.server.connect() as db:
                total=db.execute('SELECT COUNT(*)'+query,args).fetchone()[0]
                rows=db.execute('SELECT a.*,d.name AS department_name'+query+' ORDER BY a.id DESC LIMIT ? OFFSET ?',[*args,size,(page-1)*size]).fetchall()
            return self.h.send_json(200,{'items':[dict(r) for r in rows],'page':page,'pageSize':size,'total':total})
        self.error(404,'NOT_FOUND','Không có trang hoặc endpoint quản trị này.')
