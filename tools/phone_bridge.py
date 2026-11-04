"""User-space TCP bridge: LAN -> loopback ADB -> emulator. No admin/netsh/firewall edits."""
import select
import socket
import socketserver
import subprocess
import sys
import tempfile
from pathlib import Path
from connection import load_connection,locate_adb,selected_device,is_lan_ip
from phone_setup import load_pairing_setup,install_pairing_setup

PACKAGE = 'com.example.tvdashboard'


def adb_command(adb, serial, *args, input=None):
    try:
        result = subprocess.run([adb, '-s', serial, *args], input=input,
                                capture_output=True, text=True, timeout=15)
    except subprocess.TimeoutExpired:
        raise RuntimeError('ADB hết thời gian chờ. Kiểm tra emulator đã khởi động và đúng thiết bị.') from None
    if result.returncode == 0:
        return result
    detail = '\n'.join(x.strip() for x in (result.stderr or '', result.stdout or '') if x.strip())
    if input:
        # Error diagnostics must not echo setup credentials, even with an unusual adb build.
        try:
            import json
            secret = json.loads(input).get('tvKey')
            if secret:
                detail = detail.replace(secret, '[redacted]')
        except (ValueError, AttributeError):
            pass
    lower = detail.lower()
    if 'not debuggable' in lower:
        hint = 'Android Studio: Select Build Variant → debug, rồi Run lên đúng emulator.'
    elif 'unknown package' in lower:
        hint = 'Chưa có app trên thiết bị này. Run bản debug trước khi chạy bridge.'
    elif 'permission denied' in lower or 'could not set capabilities' in lower:
        hint = 'Quyền Administrator Windows không giải quyết quyền run-as của Android. Dùng app debug trên emulator hỗ trợ run-as.'
    else:
        hint = 'Kiểm tra app debug và kết nối ADB.'
    raise RuntimeError(f'ADB ({serial}) exit {result.returncode}: {hint}\n{detail}')


class BridgeHandler(socketserver.BaseRequestHandler):
    def handle(self):
        if not is_lan_ip(self.client_address[0]) or not self.server.slots.acquire(blocking=False):return
        try:
            with socket.create_connection(('127.0.0.1',self.server.adb_port),timeout=5) as upstream:
                peers=[self.request,upstream]
                while True:
                    ready,_,_=select.select(peers,[],[],30)
                    if not ready:return
                    for src in ready:
                        payload=src.recv(65536)
                        if not payload:return
                        dst=upstream if src is self.request else self.request
                        dst.settimeout(10);dst.sendall(payload)
        except OSError:pass
        finally:self.server.slots.release()

class BridgeServer(socketserver.ThreadingTCPServer):
    daemon_threads=True
    allow_reuse_address=True
    def __init__(self,address,adb_port):
        import threading
        self.adb_port=adb_port;self.slots=threading.BoundedSemaphore(32)
        super().__init__(address,BridgeHandler)

def main():
    cfg=load_connection();adb=locate_adb(cfg);serial=selected_device(adb,cfg)
    def run(*args, input=None):
        return adb_command(adb,serial,*args,input=input)
    run('shell','run-as','com.example.tvdashboard','pwd')
    pairing=load_pairing_setup(cfg)
    server=BridgeServer((cfg['pcLanIp'],cfg['phonePort']),cfg['adbPort'])
    forwarded=False
    try:
        local=f"tcp:{cfg['adbPort']}";remote=f"tcp:{cfg['tvServerPort']}"
        forwards=run('forward','--list').stdout.splitlines()
        existing=[line.split() for line in forwards if len(line.split())==3 and line.split()[1]==local]
        if existing and existing!=[[serial,local,remote]]:
            raise ValueError('adbPort đang được dùng cho thiết bị/cổng khác. Đổi adbPort trong connection.json.')
        if not existing:run('forward','--no-rebind',local,remote)
        forwarded=True
        with tempfile.TemporaryDirectory() as temp:
            file=Path(temp)/'lan-debug.properties'
            file.write_text(f"host={cfg['pcLanIp']}\nphonePort={cfg['phonePort']}\nserverPort={cfg['tvServerPort']}\n",encoding='ascii')
            target='/data/local/tmp/tvdashboard-lan.properties'
            run('push',str(file),target)
            try:
                run('shell','run-as','com.example.tvdashboard','mkdir','-p','files')
                run('shell','run-as','com.example.tvdashboard','cp',target,'files/lan-debug.properties')
            finally:run('shell','rm',target)
        install_pairing_setup(run,pairing)
        print("Ghép nối API đã sẵn sàng. Quét QR rồi nhập PIN; không cần nhập IP hoặc TV key.",flush=True)
        print(f"PHONE: http://{cfg['pcLanIp']}:{cfg['phonePort']}/",flush=True)
        print('Giữ cửa sổ này mở. Đóng/mở lại Cài đặt trong app để cập nhật QR và PIN.',flush=True)
        print('Nếu Windows hỏi mạng, chỉ cho phép Private trên Wi-Fi tin cậy. Ctrl+C để dừng.',flush=True)
        server.serve_forever()
    except KeyboardInterrupt:pass
    finally:
        server.server_close()
        if forwarded:
            subprocess.run([adb,'-s',serial,'forward','--remove',f"tcp:{cfg['adbPort']}"],capture_output=True,timeout=15)
            subprocess.run([adb,'-s',serial,'shell','run-as','com.example.tvdashboard','rm','-f','files/lan-debug.properties','files/phone-setup.json','files/phone-setup.json.new'],capture_output=True,timeout=15)

if __name__=='__main__':
    try:main()
    except Exception as e:print('ERROR:',e,file=sys.stderr);sys.exit(1)
