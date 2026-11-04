import concurrent.futures
from datetime import datetime,timezone,timedelta
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from urllib.parse import urlencode
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from config_service import ConfigService
ROOT=Path(__file__).resolve().parents[1]

class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.db=Path(self.temp.name)/'state.sqlite3'
        self.cfg={'developmentHttp':True,'dashboardBaseUrl':'http://192.168.1.50:8765','pollSeconds':300,'offlineSeconds':720,'keyRequestsPerMinute':120,'deviceRequestsPerMinute':10}
        self.keys=[('tv-all','tv-test-key',None),('tv-qa','qa-test-key',['qa-qc'])]
        self.start()
        self.device=str(uuid.uuid4())
    def start(self):
        self.server=ConfigService(('127.0.0.1',0),self.db,ROOT/'data/departments.seed.json',self.cfg,self.keys,'admin-test-key')
        self.thread=threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True);self.thread.start()
    def stop(self):
        self.server.shutdown();self.server.server_close();self.thread.join()
    def tearDown(self):
        self.stop();self.temp.cleanup()
    def request(self,method,path,body=None,headers=None):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        hdr={'x-api-key':'tv-test-key'}
        if headers is not None: hdr=headers.copy()
        raw=None
        if body is not None:
            raw=json.dumps(body,ensure_ascii=False).encode();hdr['Content-Type']='application/json'
        c.request(method,path,body=raw,headers=hdr)
        r=c.getresponse();data=r.read();status=r.status;rh=dict(r.getheaders());c.close()
        try: data=json.loads(data)
        except ValueError: pass
        return status,data,rh
    def settings(self,**overrides):
        args={'department':'qa-qc','deviceId':self.device,'name':'TV-Sảnh','appVersion':'0.1.0'};args.update(overrides)
        return '/api/v1/settings?'+urlencode(args)
    def admin(self):return {'Authorization':'Bearer admin-test-key'}
    def update(self,url='https://example.com/new',version=1):
        return self.request('PUT','/api/v1/departments/qa-qc/settings',{'dashboardUrl':url,'expectedVersion':version},self.admin())

    def test_exact_four_departments(self):
        status,data,_=self.request('GET','/api/v1/departments')
        self.assertEqual(status,200)
        self.assertEqual([d['id'] for d in data['departments']],['qa-qc','dev','project-teams','techgovernance'])

    def test_settings_contract_and_no_store(self):
        s,d,h=self.request('GET',self.settings())
        self.assertEqual(s,200);self.assertEqual(d['version'],1);self.assertEqual(d['pollSeconds'],300)
        self.assertEqual(d['phonePinPolicy'],{'mode':'random'});self.assertEqual(d['phonePinPolicyVersion'],0)
        self.assertFalse(d['diagnosticsEnabled']);self.assertEqual(d['diagnosticsPolicyVersion'],0)
        self.assertEqual(d['department']['id'],'qa-qc')
        self.assertEqual(h['Cache-Control'],'no-store')
        self.assertNotIn('reloadSeconds',d);self.assertNotIn('session',d)
    def test_missing_and_wrong_key_401_no_heartbeat(self):
        for hdr in [{},{'x-api-key':'bad'}]:
            s,d,_=self.request('GET',self.settings(),headers=hdr);self.assertEqual(s,401)
        self.assertEqual(self.request('GET','/api/v1/devices',headers=self.admin())[1]['total'],0)
    def test_revoked_key_401(self):
        with self.server.connect() as c:c.execute("UPDATE api_keys SET active=0 WHERE key_id='tv-all'")
        self.assertEqual(self.request('GET',self.settings())[0],401)
    def test_scoped_key_list_and_403(self):
        hdr={'x-api-key':'qa-test-key'}
        self.assertEqual(len(self.request('GET','/api/v1/departments',headers=hdr)[1]['departments']),1)
        self.assertEqual(self.request('GET',self.settings(department='project-teams'),headers=hdr)[0],403)
        self.assertEqual(self.request('GET',self.settings(),headers=hdr)[0],200)
    def test_unknown_department_404(self):
        self.assertEqual(self.request('GET',self.settings(department='not-found'))[0],404)
    def test_invalid_uuid_and_department_400(self):
        for fields in [{'deviceId':'x'},{'deviceId':str(uuid.uuid1())},{'department':'BAD'},{'name':'x'*65},{'name':'\n'}]:
            self.assertEqual(self.request('GET',self.settings(**fields))[0],400)
    def test_duplicate_parameter_400(self):
        self.assertEqual(self.request('GET',self.settings()+'&department=project-teams')[0],400)
    def test_successful_poll_updates_last_seen_only_once_per_id(self):
        self.request('GET',self.settings())
        self.request('GET',self.settings(name='TV mới'))
        d=self.request('GET','/api/v1/devices',headers=self.admin())[1]
        self.assertEqual(d['total'],1);self.assertEqual(d['items'][0]['name'],'TV mới');self.assertEqual(d['items'][0]['status'],'online')
    def test_same_name_different_ids_are_separate(self):
        self.request('GET',self.settings());self.request('GET',self.settings(deviceId=str(uuid.uuid4())))
        self.assertEqual(self.request('GET','/api/v1/devices',headers=self.admin())[1]['total'],2)
    def test_department_change_updates_the_same_device(self):
        self.request('GET',self.settings())
        self.assertEqual(self.request('GET',self.settings(department='project-teams'))[0],200)
        result=self.request('GET','/api/v1/devices',headers=self.admin())[1]
        self.assertEqual(result['total'],1)
        self.assertEqual(result['items'][0]['deviceId'],self.device)
        self.assertEqual(result['items'][0]['department']['id'],'project-teams')
    def test_scoped_key_cannot_move_device_or_create_duplicate(self):
        headers={'x-api-key':'qa-test-key'}
        self.assertEqual(self.request('GET',self.settings(),headers=headers)[0],200)
        self.assertEqual(self.request('GET',self.settings(department='project-teams'),headers=headers)[0],403)
        result=self.request('GET','/api/v1/devices',headers=self.admin())[1]
        self.assertEqual(result['total'],1)
        self.assertEqual(result['items'][0]['deviceId'],self.device)
        self.assertEqual(result['items'][0]['department']['id'],'qa-qc')
    def test_admin_update_readback_version_audit(self):
        self.assertEqual(self.update()[1]['version'],2)
        d=self.request('GET',self.settings())[1]
        self.assertEqual(d['dashboardUrl'],'https://example.com/new');self.assertEqual(d['version'],2)
        audit=self.request('GET','/api/v1/audit',headers=self.admin())[1]['items']
        self.assertEqual(len(audit),1);self.assertEqual(audit[0]['changed_by'],'admin-token')
    def test_same_url_preserves_version(self):
        url=self.request('GET',self.settings())[1]['dashboardUrl']
        self.assertEqual(self.update(url)[1]['version'],1)
    def test_stale_version_409(self):
        self.update();self.assertEqual(self.update(version=1)[0],409)
    def test_concurrent_updates_one_wins(self):
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results=list(pool.map(lambda u:self.update(u)[0],['https://example.com/a','https://example.com/b']))
        self.assertEqual(sorted(results),[200,409])
    def test_invalid_urls_do_not_change_version(self):
        for url in ['javascript:alert(1)','file:///a','https://a:b@example.com','http://a:99999','https://example.com/a b']:
            self.assertEqual(self.update(url)[0],400)
        self.assertEqual(self.request('GET',self.settings())[1]['version'],1)
    def test_tv_key_cannot_use_admin_endpoint(self):
        s,_,_=self.request('PUT','/api/v1/departments/qa-qc/settings',{'dashboardUrl':'https://example.com','expectedVersion':1})
        self.assertEqual(s,401)
        self.assertEqual(self.request('GET','/api/v1/devices')[0],401)
    def test_noninteger_version_400(self):
        self.assertEqual(self.update(version=True)[0],400)
    def test_restart_persists_configuration_and_device(self):
        self.update();self.request('GET',self.settings());self.stop();self.start()
        self.assertEqual(self.request('GET',self.settings())[1]['version'],2)
        self.assertEqual(self.request('GET','/api/v1/devices',headers=self.admin())[1]['total'],1)
    def test_offline_after_12_minutes_and_archive_after_30_days(self):
        self.request('GET',self.settings())
        stamp=(datetime.now(timezone.utc)-timedelta(minutes=13)).isoformat().replace('+00:00','Z')
        with self.server.connect() as c:c.execute('UPDATE devices SET last_seen_at=?',(stamp,))
        self.assertEqual(self.request('GET','/api/v1/devices',headers=self.admin())[1]['items'][0]['status'],'offline')
        stamp=(datetime.now(timezone.utc)-timedelta(days=31)).isoformat().replace('+00:00','Z')
        with self.server.connect() as c:c.execute('UPDATE devices SET last_seen_at=?',(stamp,))
        self.assertEqual(self.request('GET','/api/v1/devices',headers=self.admin())[1]['total'],0)
        self.request('GET',self.settings())
        self.assertEqual(self.request('GET','/api/v1/devices',headers=self.admin())[1]['total'],1)
    def test_actual_device_rate_limit(self):
        self.server.config['deviceRequestsPerMinute']=2
        self.assertEqual(self.request('GET',self.settings())[0],200)
        self.assertEqual(self.request('GET',self.settings())[0],200)
        self.assertEqual(self.request('GET',self.settings())[0],429)
    def test_missing_settings_409(self):
        with self.server.connect() as c:c.execute("DELETE FROM settings WHERE department_id='qa-qc'")
        self.assertEqual(self.request('GET',self.settings())[0],409)
    def test_dashboard_escapes_html(self):
        with self.server.connect() as c:c.execute("UPDATE departments SET name=? WHERE id='qa-qc'",('<script>alert(1)</script>',))
        s,d,_=self.request('GET','/sample-dashboard/qa-qc')
        self.assertEqual(s,200);self.assertIn(b'&lt;script&gt;',d);self.assertNotIn(b'<script>alert',d)
    def test_invalid_pagination_400(self):
        self.assertEqual(self.request('GET','/api/v1/devices?pageSize=101',headers=self.admin())[0],400)

if __name__=='__main__':unittest.main()
