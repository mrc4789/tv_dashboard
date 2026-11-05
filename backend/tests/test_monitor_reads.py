import unittest
from contextlib import contextmanager
from unittest.mock import patch
import test_admin

class MonitorReads(test_admin.AdminTests):
    def test_monitor_reads_have_two_selects_and_no_writes(self):
        self.request('GET', self.settings());self.login()
        connect=self.server.connect
        for path in ('/api/v1/devices','/admin/api/devices/'+self.device+'/phone-pin','/api/v1/devices/'+self.device+'/diagnostics'):
            statements=[]
            @contextmanager
            def traced():
                with connect() as conn:
                    conn.set_trace_callback(statements.append)
                    yield conn
            with patch.object(self.server,'connect',traced):
                self.assertEqual(self.portal('GET',path)[0],200)
            self.assertEqual(len(statements),2,(path,statements))
            self.assertTrue(all(q.lstrip().upper().startswith('SELECT') for q in statements))

for name in dir(test_admin.AdminTests):
    if name.startswith('test_') and name not in MonitorReads.__dict__:setattr(MonitorReads,name,None)
