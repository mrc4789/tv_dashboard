"""Authenticated SSE signals only; URLs are fetched through the existing settings API."""
import json
from config_service import ApiError

def stream_settings_events(handler):
    key=handler.tv_auth();dep=handler.param('department');handler.scope(key,dep);handler.department(dep)
    if not handler.server.event_slots.acquire(blocking=False):raise ApiError(429,'RATE_LIMITED','Kênh cập nhật nhanh đã đủ kết nối.',{'Retry-After':'60'})
    server=handler.server
    handler.close_connection=True
    try:
        with server.event_condition:sequence=server.event_sequence[dep]
        handler.send_response(200)
        for name,value in [('Content-Type','text/event-stream; charset=utf-8'),('Cache-Control','no-store'),('X-Accel-Buffering','no'),('Connection','close')]:handler.send_header(name,value)
        handler.end_headers()
        def event(name):
            payload=json.dumps({'department':dep},separators=(',',':'))
            handler.wfile.write(('event: '+name+'\ndata: '+payload+'\n\n').encode('utf-8'));handler.wfile.flush()
        event('ready')
        while True:
            with server.event_condition:
                server.event_condition.wait_for(lambda:server.events_closed or server.event_sequence[dep]!=sequence,timeout=server.event_heartbeat_seconds)
                if server.events_closed:break
                changed=server.event_sequence[dep]!=sequence;sequence=server.event_sequence[dep]
            # A revoked key stops receiving signals, including on a quiet connection.
            with server.connect() as db:
                row=db.execute('SELECT active,scope_json FROM api_keys WHERE key_id=? AND key_hash=?',(key['key_id'],key['key_hash'])).fetchone()
                department=db.execute('SELECT active FROM departments WHERE id=?',(dep,)).fetchone()
            if not row or not row['active'] or not department or not department['active']:break
            allowed=json.loads(row['scope_json']) if row['scope_json'] else None
            if allowed is not None and dep not in allowed:break
            if changed:event('settings-changed')
            else:handler.wfile.write(b': keepalive\n\n');handler.wfile.flush()
    except (OSError,TimeoutError):pass
    finally:server.event_slots.release()
