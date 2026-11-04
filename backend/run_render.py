"""Disposable demo backend for a Render Free web service."""
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
    if (min(len(tv_key), len(admin_token)) < 32 or tv_key == admin_token
            or not 12 <= len(password) <= 256):
        raise ValueError('Thiếu key (>=32), admin token (>=32) hoặc mật khẩu admin (12..256).')
    return domain, port, tv_key, admin_token, password


def main():
    domain, port, tv_key, admin_token, password = settings(os.environ)
    public = 'https://' + domain
    config = {'dashboardBaseUrl': public, 'publicOrigin': public,
              'developmentHttp': False, 'pollSeconds': 300, 'offlineSeconds': 720,
              'keyRequestsPerMinute': 120, 'deviceRequestsPerMinute': 10}
    runtime = Path(tempfile.mkdtemp(prefix='tvdashboard-render-'))
    server = ConfigService(('0.0.0.0', port), runtime / 'config.sqlite3',
                           ROOT / 'backend/data/departments.seed.json', config,
                           [('cloud-demo', tv_key, None)], admin_token)
    bootstrap = runtime / 'admin-bootstrap.json'
    bootstrap.write_text(json.dumps({'username': 'admin', 'password': password}), encoding='utf-8')
    try:
        provision_admin(server, bootstrap)
    finally:
        bootstrap.unlink(missing_ok=True)
    print('Render demo listening on PORT; database is temporary.', flush=True)
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
