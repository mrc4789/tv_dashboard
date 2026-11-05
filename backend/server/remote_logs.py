"""Opt-in structured diagnostics. No free-form messages, URLs, keys or PINs."""
import re
import uuid
from datetime import datetime, timezone, timedelta

KINDS = {'NETWORK', 'HTTP_SERVER', 'SSL', 'TIMEOUT', 'RENDERER', 'LOADED','LOAD_ERROR'}
DEVICE_PATH = re.compile(r'/api/v1/devices/([0-9a-f-]{36})/diagnostics(/ack)?')


def init(db):
    db.executescript("""CREATE TABLE IF NOT EXISTS diagnostic_policy(
      device_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 1);
    CREATE TABLE IF NOT EXISTS diagnostic_logs(
      device_id TEXT NOT NULL, event_id TEXT NOT NULL, kind TEXT NOT NULL, code TEXT NOT NULL,
      occurred_at TEXT NOT NULL, received_at TEXT NOT NULL, PRIMARY KEY(device_id,event_id));
    CREATE TABLE IF NOT EXISTS diagnostic_ack(
      device_id TEXT PRIMARY KEY, enabled INTEGER NOT NULL, policy_version INTEGER NOT NULL,
      received_at TEXT NOT NULL);""")
    db.execute('CREATE INDEX IF NOT EXISTS diag_time ON diagnostic_logs(received_at)')
    db.execute('CREATE INDEX IF NOT EXISTS diag_device_time ON diagnostic_logs(device_id,received_at)')
    policy_columns = {row['name'] for row in db.execute('PRAGMA table_info(diagnostic_policy)')}
    if 'version' not in policy_columns:
        db.execute('ALTER TABLE diagnostic_policy ADD COLUMN version INTEGER NOT NULL DEFAULT 1')
    log_columns = {row['name'] for row in db.execute('PRAGMA table_info(diagnostic_logs)')}
    if 'occurred_at' not in log_columns:
        db.execute("ALTER TABLE diagnostic_logs ADD COLUMN occurred_at TEXT NOT NULL DEFAULT ''")
        db.execute("UPDATE diagnostic_logs SET occurred_at=received_at WHERE occurred_at=''")


def enabled(db, device):
    row = db.execute('SELECT enabled FROM diagnostic_policy WHERE device_id=?', (device,)).fetchone()
    return bool(row and row[0])


def _event_time(value):
    if not isinstance(value, str) or len(value) > 40:
        return False
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        return parsed.tzinfo is not None
    except ValueError:
        return False


def handle(h):
    from config_service import ApiError, utcnow
    match = DEVICE_PATH.fullmatch(h.path_only)
    if not match:
        return False
    device, is_ack = match.group(1), bool(match.group(2))
    try:
        if str(uuid.UUID(device)) != device:
            raise ValueError()
    except ValueError:
        raise ApiError(400, 'INVALID_ARGUMENT', 'Device ID không hợp lệ.')

    if h.command == 'POST':
        key = h.tv_auth()
        h.server.limit(key['key_id'], device)
    elif not is_ack and h.command in ('GET', 'PUT', 'DELETE'):
        h.admin_auth()
    else:
        raise ApiError(405, 'METHOD_NOT_ALLOWED', 'Phương thức không hỗ trợ.')

    data = h.body() if h.command == 'POST' or h.command == 'PUT' else {}
    cutoff=(datetime.now(timezone.utc)-timedelta(days=7)).isoformat().replace('+00:00','Z')
    if h.command=='GET':
        with h.server.connect() as db:
            rows=db.execute("""SELECT coalesce(p.enabled,0) AS enabled,coalesce(p.version,0) AS version,
              a.enabled AS ack_enabled,a.policy_version,a.received_at AS ack_at,l.kind,l.code,l.occurred_at,l.received_at
              FROM devices d LEFT JOIN diagnostic_policy p USING(device_id) LEFT JOIN diagnostic_ack a USING(device_id)
              LEFT JOIN diagnostic_logs l ON l.device_id=d.device_id AND l.received_at>=?
              WHERE d.device_id=? ORDER BY l.received_at DESC,l.rowid DESC LIMIT 100""",(cutoff,device)).fetchall()
        if not rows:raise ApiError(404,'DEVICE_NOT_FOUND','TV chưa đăng ký.')
        r=rows[0]
        h.send_json(200,{'enabled':bool(r['enabled']),'policyVersion':r['version'],'tvAcknowledged':r['ack_enabled']==r['enabled'] and r['policy_version']==r['version'],'acknowledgedAt':r['ack_at'],'items':[{k:item[k] for k in ('kind','code','occurred_at','received_at')} for item in rows if item['kind']]})
        return True
    with h.server.connect() as db:
        db.execute('BEGIN IMMEDIATE')
        tv = db.execute('SELECT * FROM devices WHERE device_id=?', (device,)).fetchone()
        if not tv:
            raise ApiError(404, 'DEVICE_NOT_FOUND', 'TV chưa đăng ký.')

        if is_ack:
            if set(data) != {'enabled', 'version'} or type(data['enabled']) is not bool or type(data['version']) is not int or data['version'] < 0:
                raise ApiError(400, 'INVALID_ARGUMENT', 'Xác nhận chính sách không hợp lệ.')
            owner = db.execute('SELECT key_id FROM device_bindings WHERE device_id=?', (device,)).fetchone()
            if (owner[0] if owner else tv['last_key_id']) != key['key_id']:
                raise ApiError(403, 'DEVICE_KEY_MISMATCH', 'Key không thuộc TV.')
            h.scope(key, tv['department_id'])
            policy = db.execute('SELECT enabled,version FROM diagnostic_policy WHERE device_id=?', (device,)).fetchone()
            current_enabled, current_version = (bool(policy['enabled']), policy['version']) if policy else (False, 0)
            if data['version'] != current_version or data['enabled'] != current_enabled:
                raise ApiError(409, 'POLICY_CHANGED', 'Chính sách log đã thay đổi; TV cần lấy lại cấu hình.')
            db.execute('INSERT OR REPLACE INTO diagnostic_ack VALUES(?,?,?,?)',
                       (device, int(data['enabled']), data['version'], utcnow()))
            return_value = {'acknowledged': True, 'enabled': current_enabled, 'version': current_version}
        elif h.command == 'POST':
            owner = db.execute('SELECT key_id FROM device_bindings WHERE device_id=?', (device,)).fetchone()
            if (owner[0] if owner else tv['last_key_id']) != key['key_id']:
                raise ApiError(403, 'DEVICE_KEY_MISMATCH', 'Key không thuộc TV.')
            h.scope(key, tv['department_id'])
            if not enabled(db, device):
                raise ApiError(403, 'LOGGING_DISABLED', 'Gửi log đang tắt.')
            events = data.get('events')
            if set(data) != {'events'} or not isinstance(events, list) or not 1 <= len(events) <= 20:
                raise ApiError(400, 'INVALID_ARGUMENT', 'Gửi 1–20 sự kiện.')
            clean = []
            for event in events:
                if not isinstance(event, dict) or set(event) != {'id', 'kind', 'code', 'occurredAt'}:
                    raise ApiError(400, 'INVALID_ARGUMENT', 'Sự kiện không hợp lệ.')
                try:
                    if not isinstance(event['id'], str) or str(uuid.UUID(event['id'])) != event['id']:
                        raise ValueError()
                except (ValueError, TypeError, AttributeError):
                    raise ApiError(400, 'INVALID_ARGUMENT', 'ID sự kiện không hợp lệ.')
                if (not isinstance(event['kind'], str) or event['kind'] not in KINDS or
                        not isinstance(event['code'], str) or not re.fullmatch(r'[0-9]{0,3}', event['code']) or
                        not _event_time(event['occurredAt'])):
                    raise ApiError(400, 'INVALID_ARGUMENT', 'Loại, mã hoặc thời điểm sự kiện không hợp lệ.')
                clean.append((device, event['id'], event['kind'], event['code'], event['occurredAt'], utcnow()))
            db.executemany('''INSERT OR IGNORE INTO diagnostic_logs
              (device_id,event_id,kind,code,occurred_at,received_at) VALUES(?,?,?,?,?,?)''', clean)
            db.execute('DELETE FROM diagnostic_logs WHERE device_id=? AND rowid NOT IN '
                       '(SELECT rowid FROM diagnostic_logs WHERE device_id=? ORDER BY received_at DESC,rowid DESC LIMIT 250)',
                       (device, device))
            db.execute('DELETE FROM diagnostic_logs WHERE received_at<?',(cutoff,))
            db.execute('DELETE FROM diagnostic_logs WHERE rowid IN (SELECT rowid FROM diagnostic_logs ORDER BY received_at DESC,rowid DESC LIMIT -1 OFFSET 50000)')
            return_value = {'accepted': len(clean)}
        elif h.command == 'PUT':
            if set(data) != {'enabled'} or type(data['enabled']) is not bool:
                raise ApiError(400, 'INVALID_ARGUMENT', 'enabled phải là boolean.')
            db.execute('''INSERT INTO diagnostic_policy(device_id,enabled,version) VALUES(?,?,1)
              ON CONFLICT(device_id) DO UPDATE SET
              version=diagnostic_policy.version + CASE WHEN diagnostic_policy.enabled!=excluded.enabled THEN 1 ELSE 0 END,
              enabled=excluded.enabled''', (device, int(data['enabled'])))
            return_value = {'ok': True}
        elif h.command == 'DELETE':
            db.execute('DELETE FROM diagnostic_logs WHERE device_id=?', (device,))
            return_value = {}
        if h.command in ('GET', 'PUT', 'DELETE'):
            policy = db.execute('SELECT enabled,version FROM diagnostic_policy WHERE device_id=?', (device,)).fetchone()
            ack = db.execute('SELECT enabled,policy_version,received_at FROM diagnostic_ack WHERE device_id=?', (device,)).fetchone()
            current_enabled, version = (bool(policy['enabled']), policy['version']) if policy else (False, 0)
            return_value.update({
                'enabled': current_enabled,
                'policyVersion': version,
                'tvAcknowledged': bool(ack and ack['enabled'] == int(current_enabled) and ack['policy_version'] == version),
                'acknowledgedAt': ack['received_at'] if ack else None,
                'items': [dict(row) for row in db.execute(
                    'SELECT kind,code,occurred_at,received_at FROM diagnostic_logs '
                    'WHERE device_id=? ORDER BY received_at DESC,rowid DESC LIMIT 100', (device,))]
            })
    h.send_json(200, return_value)
    return True
