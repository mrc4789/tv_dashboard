import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from home_setup_core import HomeSetup, SetupError, COMPONENT, endpoint

OLD='com.google.android.tvlauncher/.MainActivity'
class FakeADB:
    def __init__(self):
        self.home=OLD;self.state='device';self.installed=True;self.refuse=False
        self.disabled=False;self.calls=[];self.launch_error=False;self.disable_error=False
    def __call__(self,a):
        self.calls.append(a)
        if a[1:] == ['devices']:return 'List of devices attached\nemulator-5554\t'+self.state+'\n'
        assert a[1:4]==['-s','emulator-5554','shell'],a
        c=a[4:]
        if c==['am','get-current-user']:return '10'
        if c[:3]==['cmd','package','resolve-activity']:return 'priority=2\n'+self.home
        if c[:3]==['cmd','package','query-activities']:return OLD+'\n'+COMPONENT
        if c[:2]==['pm','path']:return 'package:/data/app/base.apk' if self.installed else ''
        if c[:2]==['pm','enable']:
            if c[-1] == OLD.split('/')[0]:self.disabled=False
            return 'new state: enabled'
        if c[:2]==['pm','disable-user']:
            if self.disable_error:return 'Error: not allowed'
            self.disabled=True;return 'new state: disabled-user'
        if c[:3]==['cmd','package','set-home-activity']:
            if c[-1]==OLD or not self.refuse or self.disabled:self.home=c[-1]
            return 'Success'
        if c[:2]==['am','start']:return 'Error: Activity not found' if self.launch_error else 'Starting'
        raise AssertionError(a)
class Tests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.fake=FakeADB();self.core=HomeSetup('adb',self.temp.name,self.fake)
    def test_devices(self):self.assertEqual(self.core.devices(),[('emulator-5554','device')])
    def test_offline(self):
        self.fake.state='offline'
        with self.assertRaises(SetupError):self.core.set_home('emulator-5554')
        self.assertEqual(len(self.fake.calls),1)
    def test_unauthorized(self):
        self.fake.state='unauthorized'
        with self.assertRaises(SetupError):self.core.inspect('emulator-5554')
    def test_missing_app(self):
        self.fake.installed=False
        with self.assertRaises(SetupError):self.core.set_home('emulator-5554')
        self.assertEqual(self.fake.home,OLD)
    def test_success_verified_and_user(self):
        self.assertIn('đã là Dashboard',self.core.set_home('emulator-5554'))
        select=[c for c in self.fake.calls if 'set-home-activity' in c][0]
        self.assertIn('10',select)
        self.assertFalse(self.fake.disabled)
    def test_false_success(self):
        self.fake.refuse=True
        self.assertIn('CHƯA THÀNH CÔNG',self.core.set_home('emulator-5554'))
        self.assertFalse(self.fake.disabled)
    def test_disable_and_restore(self):
        self.fake.refuse=True;state=self.core.inspect('emulator-5554')
        self.core.disable_old(state);self.assertTrue(self.fake.disabled)
        self.core.restore('emulator-5554');self.assertFalse(self.fake.disabled);self.assertEqual(self.fake.home,OLD)
    def test_stale_confirmation(self):
        state=self.core.inspect('emulator-5554');self.fake.home='other.home/.Main'
        with self.assertRaises(SetupError):self.core.disable_old(state)
        self.assertFalse(self.fake.disabled)
    def test_protect_settings(self):
        self.fake.home='com.android.tv.settings/.FallbackHome'
        with self.assertRaises(SetupError):self.core.disable_old(self.core.inspect('emulator-5554'))
        self.assertFalse(self.fake.disabled)
    def test_verify_app_launch_before_disable(self):
        self.fake.launch_error=True
        with self.assertRaises(SetupError):self.core.disable_old(self.core.inspect('emulator-5554'))
        self.assertFalse(self.fake.disabled)
    def test_rollback_error_output(self):
        self.fake.disable_error=True
        with self.assertRaisesRegex(SetupError,'Đã khôi phục'):
            self.core.disable_old(self.core.inspect('emulator-5554'))
        self.assertEqual(self.fake.home,OLD)
    def test_backup_before_disable(self):
        orig=self.fake.__call__
        def run(a):
            if 'disable-user' in a:self.assertTrue(list(Path(self.temp.name).glob('*.json')))
            return orig(a)
        self.core.runner=run
        self.core.disable_old(self.core.inspect('emulator-5554'))
    def test_restore_without_resolver(self):
        self.core.set_home('emulator-5554');self.fake.home='no activities'
        self.core.restore('emulator-5554');self.assertEqual(self.fake.home,OLD)
    def test_endpoint_validation(self):
        self.assertEqual(endpoint('192.168.1.2:5555'),'192.168.1.2:5555')
        for s in ['192.168.1.2;rm:5555','192.168.1.2:0','192.168.1.2:99999','hello','999.1.1.1:4']:
            with self.assertRaises(SetupError):endpoint(s)
    def test_timeout(self):
        import subprocess
        def fail(a):raise subprocess.TimeoutExpired(a,25)
        self.core.runner=fail
        with self.assertRaisesRegex(SetupError,'quá thời gian'):self.core.devices()
    def test_missing_adb(self):
        def fail(a):raise FileNotFoundError()
        self.core.runner=fail
        with self.assertRaisesRegex(SetupError,'Không tìm thấy'):self.core.devices()
    def test_unknown_home_stops(self):
        self.fake.home='No activity found'
        with self.assertRaises(SetupError):self.core.set_home('emulator-5554')
        self.assertFalse(any('enable' in a for a in self.fake.calls))
if __name__=='__main__':unittest.main()
