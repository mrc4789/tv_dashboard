import subprocess
import sys
from pathlib import Path
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools'))
import phone_bridge

class AdbErrorTests(unittest.TestCase):
    def result(self,stderr='',stdout='',returncode=1):
        return subprocess.CompletedProcess([],returncode,stdout,stderr)

    def call(self,result):
        with patch('phone_bridge.subprocess.run',return_value=result):
            return phone_bridge.adb_command('adb.exe','emulator-5554','shell','run-as',phone_bridge.PACKAGE,'pwd')

    def test_not_debuggable_has_run_debug_instruction(self):
        with self.assertRaises(RuntimeError) as caught:
            self.call(self.result(stderr='run-as: package not debuggable: com.example.tvdashboard'))
        message=str(caught.exception)
        self.assertIn('Select Build Variant',message);self.assertIn('emulator-5554',message)
        self.assertIn('run-as: package not debuggable',message)

    def test_unknown_package_from_stdout_has_install_instruction(self):
        with self.assertRaises(RuntimeError) as caught:
            self.call(self.result(stdout='run-as: unknown package: com.example.tvdashboard'))
        self.assertIn('Chưa có app',str(caught.exception));self.assertIn('unknown package',str(caught.exception))

    def test_permission_error_does_not_suggest_admin(self):
        with self.assertRaises(RuntimeError) as caught:
            self.call(self.result(stderr='run-as: could not set capabilities: Permission denied'))
        self.assertIn('Administrator Windows không giải quyết',str(caught.exception))

    def test_unrecognized_error_retains_both_streams(self):
        with self.assertRaises(RuntimeError) as caught:
            self.call(self.result(stderr='custom device error',stdout='extra context'))
        message=str(caught.exception)
        self.assertIn('custom device error',message);self.assertIn('extra context',message)

    def test_empty_error_shows_exit_code(self):
        with self.assertRaises(RuntimeError) as caught:self.call(self.result(returncode=7))
        self.assertIn('exit 7',str(caught.exception))

    def test_success_preserves_output_and_arguments(self):
        response=self.result(stdout='/data/user/0/com.example.tvdashboard',returncode=0)
        with patch('phone_bridge.subprocess.run',return_value=response) as run:
            self.assertIs(phone_bridge.adb_command('adb.exe','emulator-5554','shell','run-as',phone_bridge.PACKAGE,'pwd'),response)
        self.assertEqual(run.call_args.args[0],['adb.exe','-s','emulator-5554','shell','run-as','com.example.tvdashboard','pwd'])

    def test_timeout_gives_emulator_hint(self):
        with patch('phone_bridge.subprocess.run',side_effect=subprocess.TimeoutExpired('adb',15)):
            with self.assertRaisesRegex(RuntimeError,'emulator'):
                phone_bridge.adb_command('adb','emulator-5554','shell','run-as',phone_bridge.PACKAGE,'pwd')
