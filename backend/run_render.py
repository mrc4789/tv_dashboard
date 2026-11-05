"""Render backend with explicit storage selection."""
import json
import os
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend/server'))
from config_service import ConfigService
from admin_portal import provision_admin


def settings(environ):
    domain = environ.get('RENDER_EXTERNAL_HOSTNAME', '').strip().lower()
    if not re.fullmatch(r'[a-z0-9-]+(?:\.[a-z0-9-]+)*\.onrender\.com', domain):
        raise ValueError('RENDER_EXTERNAL_HOSTNAME không hợp lệ hoặc chưa được Render cấp.')
    try:
        port = int(environ.get('PORT', '10000'))
    except ValueError:
        raise ValueError('PORT phải là số nguyên.') from None
    if not 1024 <= port <= 65535:
        raise ValueError('PORT phải thuộc 1024..65535.')
    tv_key = environ.get('TV_CLOUD_TV_KEY', '')
    admin_token = environ.get('TV_CLOUD_ADMIN_TOKEN', '')
    password = environ.get('TV_CLOUD_ADMIN_PASSWORD', '')
    if ((tv_key and (not 32 <= len(tv_key) <= 256 or not all(33 <= ord(c) <= 126 for c in tv_key))) or len(admin_token) < 32 or tv_key == admin_token
            or not 12 <= len(password) <= 256):
        raise ValueError('Kiểm tra admin token (>=32), mật khẩu (12..256) và TV key cũ nếu có (32..256).')
    return domain, port, tv_key, admin_token, password


def storage(env, config):
    url, token = env.get('TURSO_DATABASE_URL','').strip(), env.get('TURSO_AUTH_TOKEN','').strip()
    directory = env.get('TV_DATA_DIR','').strip()
    if (url or token) and directory:
        raise ValueError('Chỉ chọn Turso hoặc TV_DATA_DIR.')
    if url or token:
        from turso_store import endpoint
        if not url or not token: raise ValueError('Cần cả TURSO_DATABASE_URL và TURSO_AUTH_TOKEN.')
        endpoint(url)
        config.update(tursoUrl=url,tursoToken=token)
        return ':remote:'
    if directory:
        path=Path(directory)
        if not path.is_absolute() or not path.is_dir() or not os.access(path,os.W_OK):
            raise ValueError('TV_DATA_DIR phải trỏ đến thư mục ổ bền vững đã mount, có quyền ghi.')
        if env.get('RENDER')=='true' and not path.is_mount():
            raise ValueError('TV_DATA_DIR phải đúng mount path của Persistent Disk trên Render.')
        return path / 'config.sqlite3'
    raise ValueError('Chưa có DB bền vững: cấu hình Turso hoặc gắn Persistent Disk và đặt TV_DATA_DIR.')


def main():
    domain, port, tv_key, admin_token, password = settings(os.environ)
    public = 'https://' + domain
    config = {'dashboardBaseUrl': public, 'publicOrigin': public,
              'developmentHttp': False, 'pollSeconds': 300, 'offlineSeconds': 720,
              'keyRequestsPerMinute': 120, 'deviceRequestsPerMinute': 10}
    db_path = storage(os.environ, config)
    keys = [('cloud-demo', tv_key, None)] if tv_key else []
    server = ConfigService(('0.0.0.0', port), db_path,
                           ROOT / 'backend/data/departments.seed.json', config, keys, admin_token)
    if not tv_key:
        with server.connect() as c:
            c.execute("UPDATE api_keys SET active=0 WHERE key_id='cloud-demo'")
    with tempfile.TemporaryDirectory() as tmp:
        bootstrap = Path(tmp) / 'bootstrap.json'
        bootstrap.write_text(json.dumps({'username': 'admin', 'password': password}), encoding='utf-8')
        provision_admin(server, bootstrap)
    print('Render ready; storage: '+('Turso' if config.get('tursoUrl') else str(db_path)), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print('ERROR:', exc, file=sys.stderr)
        sys.exit(1)
