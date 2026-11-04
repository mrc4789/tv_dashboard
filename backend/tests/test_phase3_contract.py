from urllib.parse import urlencode
import uuid
import test_api

class Phase3ContractTests(test_api.ApiTests):
    def test_app_boot_poll_update_readback_and_last_seen(self):
        args={'department':'qa-qc','deviceId':str(uuid.uuid4()),'name':'TV QA / Phòng 1 & 2','appVersion':'0.3.0'}
        endpoint='/api/v1/settings?'+urlencode(args)
        status,first,_=self.request('GET',endpoint);self.assertEqual(status,200);self.assertEqual(first['pollSeconds'],300)
        self.assertEqual(self.update('https://example.com/qa-new',1)[0],200)
        status,new,_=self.request('GET',endpoint);self.assertEqual(status,200);self.assertEqual(new['version'],2);self.assertEqual(new['dashboardUrl'],'https://example.com/qa-new')
        with self.server.connect() as db:
            rows=db.execute('SELECT * FROM devices').fetchall()
        self.assertEqual(len(rows),1);self.assertEqual(rows[0]['device_id'],args['deviceId']);self.assertEqual(rows[0]['name'],args['name']);self.assertEqual(rows[0]['app_version'],'0.3.0')
    def test_department_list_does_not_emit_ping(self):
        self.assertEqual(self.request('GET','/api/v1/departments')[0],200)
        with self.server.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM devices').fetchone()[0],0)
    def test_settings_failure_never_replaces_device_last_seen(self):
        self.request('GET',self.settings())
        with self.server.connect() as db:before=db.execute('SELECT last_seen_at FROM devices').fetchone()[0]
        self.assertEqual(self.request('GET',self.settings(),headers={'x-api-key':'revoked-key'})[0],401)
        self.assertEqual(self.request('GET',self.settings(department='missing'))[0],404)
        with self.server.connect() as db:self.assertEqual(db.execute('SELECT last_seen_at FROM devices').fetchone()[0],before)

for name in dir(test_api.ApiTests):
    if name.startswith('test_'):setattr(Phase3ContractTests,name,None)
