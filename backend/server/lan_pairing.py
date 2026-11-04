"""Short-lived, trusted-LAN enrollment. Discovery never publishes an API key.

This intentionally trusts hosts on the private LAN during the enrollment window.
The TV's PIN authorizes the phone locally; it is NOT server/admin authentication.
Disable lanPairingSeconds (0) outside a trusted installation network.
"""
import hashlib
import hmac
import ipaddress
import json
import secrets
import socket
import threading
import time
import uuid
from collections import defaultdict, deque

DISCOVERY_PORT = 8766
PROTOCOL = 'tvdashboard-pairing-v1'

def private_ipv4(value, allow_loopback=False):
    try:
        ip = ipaddress.IPv4Address(value)
        return (allow_loopback and ip.is_loopback) or any(ip in ipaddress.ip_network(n) for n in (
            '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
    except ipaddress.AddressValueError:
        return False

class LanPairing:
    def __init__(self, server, seconds=600, port=DISCOVERY_PORT, allow_loopback=False):
        self.server = server
        self.allow_loopback = allow_loopback
        self.deadline = time.monotonic() + seconds
        self.lock = threading.Lock()
        self.tokens = {}
        self.attempts = defaultdict(deque)
        self.issued = 0
        self.stop_event = threading.Event()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            self.sock.bind(('0.0.0.0', port))
            self.sock.settimeout(.25)
        except Exception:
            self.sock.close()
            raise
        self.port = self.sock.getsockname()[1]
        self.thread = threading.Thread(target=self.listen, name='lan-pairing', daemon=True)
        self.thread.start()

    def close(self):
        self.stop_event.set()
        self.sock.close()
        self.thread.join(timeout=1)

    def advertisement(self, request, peer):
        if not private_ipv4(peer, self.allow_loopback): return None
        if not isinstance(request, dict) or request.get('protocol') != PROTOCOL: return None
        nonce = request.get('nonce')
        if not isinstance(nonce, str) or len(nonce) != 32 or any(c not in '0123456789abcdef' for c in nonce): return None
        now = time.monotonic()
        with self.lock:
            # Bound memory as well as per-host requests.
            self.tokens = {k:v for k,v in self.tokens.items() if v[1] > now}
            for host in list(self.attempts):
                while self.attempts[host] and self.attempts[host][0] <= now-60: self.attempts[host].popleft()
                if not self.attempts[host]: del self.attempts[host]
            if len(self.attempts) >= 256 and peer not in self.attempts: return None
            if len(self.attempts[peer]) >= 30: return None
            self.attempts[peer].append(now)
            opened = now < self.deadline and self.issued < 64
            body = {'protocol':PROTOCOL, 'nonce':nonce, 'port':self.server.server_port,
                    'scheme':'https' if self.server.config.get('httpsCert') else 'http', 'open':opened}
            # A previously paired TV can authenticate this server at a new LAN IP.
            # Only a short fingerprint is advertised, never the key or its full hash.
            fingerprint = request.get('fingerprint')
            if isinstance(fingerprint,str) and len(fingerprint)==32 and all(c in '0123456789abcdef' for c in fingerprint):
                with self.server.connect() as db:
                    rows = db.execute('SELECT key_hash FROM api_keys WHERE active=1 AND substr(key_hash,1,32)=?',(fingerprint,)).fetchall()
                host = self.server.server_address[0]
                if len(rows)==1 and private_ipv4(host,self.allow_loopback):
                    body['host'] = host
                    message = f"{nonce}|{host}|{body['scheme']}|{body['port']}"
                    body['proof'] = hmac.new(bytes.fromhex(rows[0][0]),message.encode(),hashlib.sha256).hexdigest()
            if opened and len(self.tokens) < 256:
                token = secrets.token_urlsafe(32)
                self.tokens[token] = (peer, now+15)
                body['ticket'] = token
            return body

    def listen(self):
        while not self.stop_event.is_set():
            try:
                raw, peer = self.sock.recvfrom(1025)
                if len(raw) > 1024: continue
                result = self.advertisement(json.loads(raw), peer[0])
                if result: self.sock.sendto(json.dumps(result, separators=(',',':')).encode(), peer)
            except (ValueError, UnicodeError, socket.timeout): continue
            except OSError: break

    def enroll(self, peer, body):
        from config_service import ApiError
        if not private_ipv4(peer, self.allow_loopback):
            raise ApiError(403,'LAN_ONLY','Chỉ ghép thiết bị trong mạng LAN tin cậy.')
        device = body.get('deviceId')
        try:
            uid = uuid.UUID(device)
            if uid.version != 4 or str(uid) != device: raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise ApiError(400,'INVALID_ARGUMENT','deviceId phải là UUID v4.')
        ticket = body.get('ticket')
        if not isinstance(ticket,str) or len(ticket)>128:
            raise ApiError(400,'INVALID_ARGUMENT','Vé ghép không hợp lệ.')
        with self.lock:
            now = time.monotonic()
            if now >= self.deadline or self.issued >= 64:
                raise ApiError(403,'PAIRING_CLOSED','Hết thời gian ghép LAN. Khởi động lại service để mở 10 phút ghép mới.')
            value = self.tokens.get(ticket)
            if not value or value[0] != peer or value[1] <= now:
                raise ApiError(403,'INVALID_PAIRING_TICKET','Vé ghép đã hết hạn hoặc không hợp lệ. Thử kết nối lại.')
            del self.tokens[ticket]
            department = self.server.config.get('pairingDepartment','qa-qc')
            with self.server.connect() as db:
                deps = [r[0] for r in db.execute('SELECT id FROM departments WHERE active=1')]
                if department not in deps:
                    raise ApiError(409,'PAIRING_DEPARTMENT_INVALID','Admin cần sửa pairingDepartment trong connection.json.')
                if db.execute('SELECT 1 FROM device_bindings WHERE device_id=?',(device,)).fetchone() or db.execute('SELECT 1 FROM devices WHERE device_id=?',(device,)).fetchone():
                    raise ApiError(409,'DEVICE_ALREADY_PAIRED','TV đã ghép. Dùng key cũ hoặc yêu cầu admin cấp lại.')
                key = secrets.token_urlsafe(32)
                key_id = 'lan-' + str(uuid.uuid4())
                db.execute('INSERT INTO api_keys(key_id,key_hash,scope_json,active) VALUES(?,?,?,1)',
                           (key_id,hashlib.sha256(key.encode()).hexdigest(),json.dumps(deps)))
                db.execute('INSERT INTO device_bindings VALUES(?,?)',(device,key_id))
            self.issued += 1
        # Separate read-only key, never adminToken or the shared tv-main key.
        return {'apiKey':key, 'department':department, 'name':'TV-'+device[:8]}

def handle_enrollment(handler):
    from config_service import ApiError
    pairing = getattr(handler.server,'lan_pairing',None)
    if not pairing: raise ApiError(403,'PAIRING_DISABLED','Service chưa bật ghép LAN tự động.')
    if handler.headers.get('Origin') or handler.headers.get('Sec-Fetch-Site'):
        raise ApiError(403,'APP_ONLY','Yêu cầu ghép phải đi qua ứng dụng TV sau bước PIN.')
    result = pairing.enroll(handler.client_address[0],handler.body())
    handler.send_json(200,result)
