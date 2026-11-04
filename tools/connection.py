"""Read the shared connection.json; no privileged network changes."""
import ipaddress
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def is_lan_ip(value):
    try:
        ip = ipaddress.IPv4Address(value)
        return any(ip in ipaddress.ip_network(n) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16'))
    except ipaddress.AddressValueError:
        return False

def detect_lan_ip():
    if os.name == 'nt':
        # Read-only query; prefer the physical Wi-Fi/Ethernet adapter with a default route.
        command = "Get-NetIPConfiguration | Where-Object {$_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' -and $_.NetAdapter.HardwareInterface} | Sort-Object {(Get-NetIPInterface -InterfaceIndex $_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric} | Select-Object -First 1 | ForEach-Object {$_.IPv4Address | Select-Object -First 1 -ExpandProperty IPAddress}"
        result = subprocess.run(['powershell.exe','-NoProfile','-Command',command],capture_output=True,text=True,timeout=15,check=True)
        value = result.stdout.strip()
    else:
        # UDP connect chooses the local route; it sends no packet.
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
            sock.connect(('192.0.2.1',9));value=sock.getsockname()[0]
    if not is_lan_ip(value):
        raise ValueError('Không tìm được IP Wi-Fi/Ethernet. Nhập pcLanIp trong connection.json.')
    return value

def load_connection(path=None):
    cfg=json.loads(Path(path or ROOT/'connection.json').read_text(encoding='utf-8-sig'))
    cfg['pcLanIp']=cfg['pcLanIp'].strip() or detect_lan_ip()
    if not is_lan_ip(cfg['pcLanIp']) or cfg['pcLanIp'] in ('10.0.2.2','10.0.2.15','10.0.3.2','10.0.3.15'):
        raise ValueError('pcLanIp phải là IPv4 LAN máy tính, không phải IP emulator hay 0.0.0.0.')
    ports=[cfg[k] for k in ('apiPort','phonePort','adbPort','tvServerPort')]
    if any(type(p) is not int or not 1024<=p<=65535 for p in ports) or len(set(ports))!=4:
        raise ValueError('Bốn cổng phải khác nhau và thuộc 1024..65535.')
    if type(cfg['developmentHttp']) is not bool:
        raise ValueError('developmentHttp phải là true/false.')
    for key in ('pollSeconds','offlineSeconds','keyRequestsPerMinute','deviceRequestsPerMinute'):
        if type(cfg[key]) is not int or cfg[key]<1: raise ValueError('Giá trị không hợp lệ: '+key)
    if cfg['offlineSeconds']<=cfg['pollSeconds']: raise ValueError('offlineSeconds phải lớn hơn pollSeconds.')
    if bool(cfg['httpsCert'])!=bool(cfg['httpsKey']): raise ValueError('Nhập cả httpsCert và httpsKey.')
    seconds = cfg.setdefault('lanPairingSeconds', 0)
    if type(seconds) is not int or not 0 <= seconds <= 1800:
        raise ValueError('lanPairingSeconds phải là 0..1800; 0 tắt ghép tự động.')
    return cfg

def locate_adb(cfg):
    candidate=cfg['adbPath'] or shutil.which('adb') or shutil.which('adb.exe')
    if not candidate:
        candidate=str(Path(os.environ.get('LOCALAPPDATA',''))/'Android/Sdk/platform-tools/adb.exe')
    if not Path(candidate).is_file(): raise ValueError('Không tìm được ADB. Nhập adbPath trong connection.json.')
    return candidate

def selected_device(adb,cfg):
    result=subprocess.run([adb,'devices'],capture_output=True,text=True,timeout=15,check=True)
    devices=[line.split()[0] for line in result.stdout.splitlines() if len(line.split())==2 and line.split()[1]=='device']
    serial=cfg['deviceSerial']
    if not serial:
        if len(devices)!=1: raise ValueError('Mở một emulator, hoặc nhập deviceSerial trong connection.json.')
        serial=devices[0]
    if serial not in devices: raise ValueError('Thiết bị ADB chưa sẵn sàng hoặc chưa được cấp quyền.')
    return serial
