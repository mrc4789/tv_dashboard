"""Admin-issued enrollment invitations. The device generates and retains its own secret."""
import json
import re
import secrets
import time
import uuid

from config_service import ApiError, digest, validate_url


def issue(server, department):
    code = secrets.token_hex(6).upper()
    expires = int(time.time()) + 600
    with server.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        db.execute('DELETE FROM cloud_invites WHERE expires<=?', (int(time.time()),))
        if not db.execute('SELECT 1 FROM departments WHERE id=? AND active=1', (department,)).fetchone():
            raise ApiError(404, 'DEPARTMENT_NOT_FOUND', 'Phòng ban không còn hoạt động.')
        db.execute('INSERT INTO cloud_invites(code_hash,department,expires) VALUES(?,?,?)',
                   (digest(code), department, expires))
    return {'code': code, 'expiresIn': 600, 'department': department}


def redeem(server, body, previous=''):
    if set(body) != {'code', 'deviceId', 'deviceKey'}:
        raise ApiError(400, 'INVALID_ARGUMENT', 'Dữ liệu ghép không hợp lệ.')
    code, device, key = (body[k] for k in ('code', 'deviceId', 'deviceKey'))
    if not all(isinstance(x, str) for x in (code, device, key)) or not re.fullmatch('[A-F0-9]{12}', code) or not re.fullmatch('[a-f0-9]{64}', key):
        raise ApiError(400, 'INVALID_ARGUMENT', 'Mã ghép hoặc khóa thiết bị sai định dạng.')
    try:
        uid = uuid.UUID(device)
        if uid.version != 4 or str(uid) != device: raise ValueError()
    except ValueError:
        raise ApiError(400, 'INVALID_ARGUMENT', 'Device ID không hợp lệ.')
    owner = 'cloud-tv-' + device
    with server.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        inv = db.execute('SELECT * FROM cloud_invites WHERE code_hash=? AND expires>?', (digest(code), int(time.time()))).fetchone()
        if not inv or (inv['used_device'] and inv['used_device'] != device):
            raise ApiError(403, 'INVALID_INVITE', 'Mã ghép sai, đã dùng hoặc hết hạn.')
        dep = inv['department']
        s = db.execute('SELECT dashboard_url FROM settings JOIN departments ON id=department_id WHERE id=? AND active=1', (dep,)).fetchone()
        if not s: raise ApiError(409, 'NO_SETTINGS', 'Phòng ban chưa có cấu hình.')
        validate_url(s[0], False)
        binding = db.execute('SELECT key_id FROM device_bindings WHERE device_id=?', (device,)).fetchone()
        if not binding:
            binding = db.execute('SELECT last_key_id FROM devices WHERE device_id=?', (device,)).fetchone()
        if binding:
            k = db.execute('SELECT key_hash,active FROM api_keys WHERE key_id=?', (binding[0],)).fetchone()
            if not k or (not k['active'] and (inv['used_device'] or binding[0] != owner)) or k['key_hash'] != digest(key if binding[0] == owner else previous):
                raise ApiError(403, 'DEVICE_KEY_MISMATCH', 'Không thể nhận thiết bị đã thuộc khóa khác.')
        taken = db.execute('SELECT key_id FROM api_keys WHERE key_hash=?', (digest(key),)).fetchone()
        if taken and taken[0] != owner: raise ApiError(409, 'KEY_CONFLICT', 'Khóa đã dùng.')
        if inv['used_device']:
            # Lost response: only the original device secret may retry. Never restore an older scope.
            scope = db.execute('SELECT scope_json FROM api_keys WHERE key_id=?', (owner,)).fetchone()
            if not binding or binding[0] != owner or not scope or json.loads(scope[0]) != [dep]:
                raise ApiError(403, 'INVALID_INVITE', 'Mã ghép không còn hiệu lực.')
        else:
            db.execute('INSERT INTO api_keys VALUES(?,?,?,1) ON CONFLICT(key_id) DO UPDATE SET scope_json=excluded.scope_json,active=1',
                       (owner, digest(key), json.dumps([dep])))
            db.execute('INSERT OR REPLACE INTO device_bindings VALUES(?,?)', (device, owner))
            db.execute('UPDATE cloud_invites SET used_device=? WHERE code_hash=?', (device,digest(code)))
    return {'department': dep, 'deviceId': device}
