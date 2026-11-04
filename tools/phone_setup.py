"""Prepare read-only TV pairing defaults; never generate keys or print secrets."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def load_pairing_setup(cfg, root=ROOT):
    root = Path(root)
    path = root / 'backend/data/credentials.json'
    if not path.is_file():
        raise ValueError('Chạy Start-Service.cmd trong cùng folder trước để tạo TV key.')
    try:
        auth = json.loads(path.read_text(encoding='utf-8-sig'))
    except (ValueError, OSError):
        raise ValueError('Không đọc được credentials.json của service.') from None
    key = auth.get('tvKey') if isinstance(auth, dict) else None
    if (not isinstance(key, str) or not 32 <= len(key) <= 256
            or any(ord(c) < 33 or ord(c) > 126 for c in key)
            or key == auth.get('adminToken')):
        raise ValueError('TV key không hợp lệ; chạy service và kiểm tra credentials.json.')
    seed = json.loads((root / 'backend/data/departments.seed.json').read_text(encoding='utf-8-sig'))
    departments = [row['id'] for row in seed['departments']]
    department = cfg.get('pairingDepartment') or ('dev' if 'dev' in departments else next(iter(departments), ''))
    if not isinstance(department, str) or not re.fullmatch(r'[a-z0-9-]{1,32}', department) or department not in departments:
        raise ValueError('pairingDepartment phải là mã phòng ban có trong departments.seed.json.')
    if not cfg.get('httpsCert') and not cfg.get('developmentHttp'):
        raise ValueError('Service cần HTTPS hoặc chế độ developmentHttp cho LAN thử nghiệm.')
    scheme = 'https' if cfg.get('httpsCert') else 'http'
    return {'serverUrl': f"{scheme}://{cfg['pcLanIp']}:{cfg['apiPort']}",
            'tvKey': key, 'department': department}


def install_pairing_setup(run, setup):
    # Data goes over adb stdin straight into app-private storage, never a shell
    # argument, public /data/local/tmp file, QR URL, browser GET response or log.
    payload = json.dumps(setup, ensure_ascii=True, separators=(',', ':'))
    run('shell', 'run-as', 'com.example.tvdashboard', 'mkdir', '-p', 'files')
    command = "run-as com.example.tvdashboard sh -c 'umask 077; cat > files/phone-setup.json.new && mv files/phone-setup.json.new files/phone-setup.json'"
    run('shell', command, input=payload)
