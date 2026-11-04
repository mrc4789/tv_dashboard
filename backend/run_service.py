"""Launch the Phase 2 configuration service and admin portal with the shared connection.json."""
import argparse
import json
import os
from pathlib import Path
import secrets
import ssl
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'));sys.path.insert(0,str(ROOT/'backend/server'))
from connection import load_connection
from config_service import ConfigService

def credentials(path):
    path=Path(path)
    if not path.exists():
        path.parent.mkdir(parents=True,exist_ok=True)
        data={'tvKey':secrets.token_urlsafe(32),'adminToken':secrets.token_urlsafe(32)}
        with path.open('x',encoding='utf-8') as f: json.dump(data,f,indent=2)
        if os.name!='nt':path.chmod(0o600)
    data=json.loads(path.read_text())
    if set(data)!={'tvKey','adminToken'} or any(not isinstance(v,str) or len(v)<32 for v in data.values()) or data['tvKey']==data['adminToken']:
        raise ValueError('credentials.json không hợp lệ: cần hai secret khác nhau, tối thiểu 32 ký tự.')
    return data

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config');parser.add_argument('--db',default=str(ROOT/'backend/data/config.sqlite3'))
    parser.add_argument('--credentials',default=str(ROOT/'backend/data/credentials.json'));args=parser.parse_args()
    cfg=load_connection(args.config)
    if not cfg['httpsCert'] and not cfg['developmentHttp']:
        raise ValueError('Chế độ triển khai yêu cầu httpsCert/httpsKey. HTTP chỉ khi developmentHttp=true.')
    scheme='https' if cfg['httpsCert'] else 'http'
    cfg['dashboardBaseUrl']=f"{scheme}://{cfg['pcLanIp']}:{cfg['apiPort']}"
    auth=credentials(args.credentials)
    server=ConfigService((cfg['pcLanIp'],cfg['apiPort']),args.db,ROOT/'backend/data/departments.seed.json',cfg,[('tv-main',auth['tvKey'],None)],auth['adminToken'])
    from admin_portal import provision_admin
    provision_admin(server,ROOT/'backend/data/admin-bootstrap.json')
    if cfg['httpsCert']:
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.minimum_version=ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(ROOT/cfg['httpsCert'],ROOT/cfg['httpsKey'])
        server.socket=context.wrap_socket(server.socket,server_side=True)
    seconds = cfg.get('lanPairingSeconds', 0)
    # Discovery remains available to previously paired devices even when enrollment is closed.
    from lan_pairing import LanPairing
    try:
        server.lan_pairing = LanPairing(server, seconds)
        print('Tim lai API cho thiet bi da ghep: UDP 8766 dang bat.', flush=True)
        if seconds:
            print(f'Ghep thiet bi moi: mo {seconds // 60} phut tren LAN tin cay.', flush=True)
        else:
            print('Ghep thiet bi moi: TAT. Thiet bi da ghep van tim lai duoc API.', flush=True)
    except OSError:
        print('WARNING: Khong mo duoc UDP 8766; khong the tu tim lai IP API. Kiem tra service trung cong.', flush=True)
    print('API:',cfg['dashboardBaseUrl'],flush=True)
    print('Admin:',cfg['dashboardBaseUrl']+'/admin/',flush=True)
    print('Đăng nhập lần đầu: backend/data/admin-bootstrap.json; có thể đổi mật khẩu trên trang admin.',flush=True)
    print('Health:',cfg['dashboardBaseUrl']+'/health',flush=True)
    print('Secrets: backend/data/credentials.json (không chia sẻ file này). Ctrl+C để dừng.',flush=True)
    if scheme=='http':print('DEVELOPMENT HTTP: chỉ dùng Wi-Fi thử nghiệm tin cậy; chưa triển khai internet.',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()

if __name__=='__main__':
    try:main()
    except Exception as e:print('ERROR:',e,file=sys.stderr);sys.exit(1)
