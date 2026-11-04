"""ADB operations for the optional desktop installer. Never invoke a shell."""
import ipaddress
import json
import os
import re
import subprocess
from pathlib import Path

PACKAGE = 'com.example.tvdashboard'
COMPONENT = PACKAGE + '/.HomeLauncher'
COMPONENT_RE = re.compile(r'^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$')
class SetupError(RuntimeError):
    pass

def run_process(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=25,
                            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if result.returncode:
        raise SetupError((result.stderr or result.stdout or 'ADB thất bại').strip())
    return result.stdout.strip()

def endpoint(value):
    host, sep, port = value.strip().rpartition(':')
    if not sep:
        raise SetupError('Nhập IPv4:cổng, ví dụ 192.168.1.20:5555 (cổng do TV cung cấp).')
    try:
        ipaddress.IPv4Address(host)
        if not 1 <= int(port) <= 65535: raise ValueError()
    except ValueError:
        raise SetupError('IPv4 hoặc cổng không hợp lệ.')
    return f'{host}:{int(port)}'

class HomeSetup:
    def __init__(self, adb, backup_dir, runner=run_process):
        self.adb, self.backup_dir, self.runner = str(adb), Path(backup_dir), runner
    def call(self, *args):
        try: return self.runner([self.adb, *args])
        except subprocess.TimeoutExpired: raise SetupError('ADB quá thời gian chờ. Kiểm tra TV và kết nối.')
        except FileNotFoundError: raise SetupError('Không tìm thấy adb.exe. Chọn file trong Android SDK/platform-tools.')
    def devices(self):
        rows = []
        for line in self.call('devices').splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2: rows.append((parts[0], parts[1]))
        return rows
    def connect(self, value): return self.call('connect', endpoint(value))
    def shell(self, serial, *args):
        output = self.call('-s', serial, 'shell', *args)
        if any(line.lstrip().startswith(('Error:', 'Failure', 'Exception', 'Security exception:')) for line in output.splitlines()):
            raise SetupError(output)
        return output
    def ready(self, serial):
        if dict(self.devices()).get(serial) != 'device':
            raise SetupError('Thiết bị offline/chưa cấp quyền. Mở TV, xác nhận debugging rồi làm mới danh sách.')
    def inspect(self, serial):
        self.ready(serial)
        user = self.shell(serial, 'am', 'get-current-user').strip()
        if not user.isdecimal(): raise SetupError('Không xác định được user Android hiện tại.')
        output = self.shell(serial, 'cmd', 'package', 'resolve-activity', '--brief', '--user', user,
                            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        components = [line.strip() for line in output.splitlines() if COMPONENT_RE.fullmatch(line.strip())]
        if len(components) != 1: raise SetupError('Không xác định được Home thực tế; chưa thực hiện thay đổi.')
        return {'serial': serial, 'user': user, 'home': components[0]}
    def file(self, state):
        import hashlib
        name = hashlib.sha256((state['serial']+'|'+state['user']).encode()).hexdigest()[:24]
        return self.backup_dir / (name + '.json')
    def save(self, state):
        self.backup_dir.mkdir(parents=True, exist_ok=True)
        path = self.file(state)
        temp = path.with_suffix('.tmp')
        temp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf-8')
        os.chmod(temp, 0o600)
        os.replace(temp, path)
    def load(self, state):
        try: saved = json.loads(self.file(state).read_text(encoding='utf-8'))
        except (OSError, ValueError): raise SetupError('Chưa có bản lưu Home cũ cho thiết bị/user này.')
        if (saved.get('serial'), saved.get('user')) != (state['serial'], state['user']) or not COMPONENT_RE.fullmatch(saved.get('home','')):
            raise SetupError('Bản lưu không hợp lệ.')
        return saved
    def ensure_app(self, state):
        if not self.shell(state['serial'], 'pm', 'path', '--user', state['user'], PACKAGE).startswith('package:'):
            raise SetupError('Chưa cài TV Dashboard cho user hiện tại.')
        out = self.shell(state['serial'], 'pm', 'enable', '--user', state['user'], COMPONENT)
        if 'enabled' not in out: raise SetupError('Không bật được HomeLauncher: ' + out)
    def select(self, state, component):
        self.shell(state['serial'], 'cmd', 'package', 'set-home-activity', '--user', state['user'], component)
    def set_home(self, serial):
        state = self.inspect(serial)
        if state['home'].split('/')[0] == PACKAGE: return 'Home thực tế đã là Dashboard. Hãy thử nút Home và khởi động lại.'
        self.ensure_app(state)
        # Keep the first recovery record while the app remains installed.
        if not self.file(state).exists(): self.save(dict(state, disabled=False))
        self.select(state, COMPONENT)
        after = self.inspect(serial)
        if after['home'].split('/')[0] != PACKAGE:
            return 'CHƯA THÀNH CÔNG: hệ thống vẫn chọn ' + after['home'] + '. Không có launcher hệ thống nào bị tắt.'
        return 'Home thực tế đã là Dashboard. Tiếp tục thử Home, khởi động lại và standby.'
    def disable_old(self, expected):
        state = self.inspect(expected['serial'])
        if state != expected: raise SetupError('Thiết bị/user/Home đã thay đổi. Kiểm tra lại trước khi xác nhận.')
        pkg = state['home'].split('/')[0]
        if pkg == PACKAGE or 'settings' in pkg.lower() or pkg == 'android' or 'fallback' in state['home'].lower():
            raise SetupError('Không cho tắt Dashboard, Settings hoặc Home dự phòng.')
        candidates = self.shell(state['serial'], 'cmd', 'package', 'query-activities', '--brief', '--user', state['user'],
                                '-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
        if state['home'] not in [line.strip() for line in candidates.splitlines()]: raise SetupError('Home cũ không khớp ứng viên đang đăng ký; dừng để tránh tắt nhầm.')
        self.ensure_app(state)
        self.shell(state['serial'], 'am', 'start', '--user', state['user'], '-n', COMPONENT)
        # Save BEFORE disabling, so recovery remains possible if the process stops.
        saved = dict(state, disabled=True)
        self.save(saved)
        try:
            output = self.shell(state['serial'], 'pm', 'disable-user', '--user', state['user'], pkg)
            if 'disabled-user' not in output: raise SetupError(output)
            self.select(state, COMPONENT)
            if self.inspect(state['serial'])['home'].split('/')[0] != PACKAGE:
                raise SetupError('Home chưa chuyển sang Dashboard.')
        except Exception as exc:
            try: self.restore(state['serial'])
            except Exception as rollback: raise SetupError(f'Không hoàn tất: {exc}. Khôi phục tự động lỗi: {rollback}. Giữ debugging, dùng nút Khôi phục Home cũ.')
            raise SetupError(f'Không hoàn tất: {exc}. Đã khôi phục Home cũ.')
        return 'Đã tắt launcher cũ theo xác nhận và xác minh Home là Dashboard. Phải thử reboot/standby trước khi bàn giao.'
    def restore(self, serial):
        self.ready(serial)
        user = self.shell(serial, 'am', 'get-current-user').strip()
        saved = self.load({'serial':serial,'user':user})
        if saved.get('disabled'):
            output = self.shell(serial,'pm','enable','--user',user,saved['home'].split('/')[0])
            if 'enabled' not in output: raise SetupError('Không bật lại được launcher cũ: '+output)
        self.select(saved, saved['home'])
        if self.inspect(serial)['home'] != saved['home']: raise SetupError('Đã yêu cầu khôi phục nhưng Home thực tế chưa khớp bản lưu.')
        self.file(saved).unlink()
        return 'Đã xác minh khôi phục Home cũ: '+saved['home']
    def test_home(self, serial):
        state=self.inspect(serial)
        return self.shell(serial,'am','start','--user',state['user'],'-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
