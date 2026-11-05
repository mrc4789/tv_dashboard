import concurrent.futures
from datetime import datetime, timezone
import secrets
import uuid
import test_admin


class CloudTests(test_admin.AdminTests):
    def enable_cloud(self):
        # Simulated TLS termination. Preserve the origin enforced by admin cookie/CSRF.
        self.server.config['publicOrigin']=f'http://127.0.0.1:{self.server.server_port}'
        self.update()
        self.assertEqual(self.login()[0],200)

    def invite(self):
        status,out,_=self.portal('POST','/admin/api/cloud-codes',{'department':'qa-qc'})
        self.assertEqual(status,201)
        return out['code']

    def claim(self,code,device,key):
        return self.request('POST','/api/v1/cloud/enroll',{'code':code,'deviceId':device,'deviceKey':key},{})

    def test_unauthorized_expiry_and_owner(self):
        self.assertEqual(self.portal('POST','/admin/api/cloud-codes',{'department':'dev'})[0],401)
        self.enable_cloud()
        self.assertEqual(self.portal('POST','/admin/api/cloud-codes',{'department':'dev'},headers={'X-CSRF-Token':'bad'})[0],403)
        code=self.invite();secret=secrets.token_hex(32)
        with self.server.connect() as db:db.execute('UPDATE cloud_invites SET expires=0')
        self.assertEqual(self.claim(code,self.device,secret)[0],403)
        code=self.invite()
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            devices=[self.device,str(uuid.uuid4())]
            results=list(pool.map(lambda d:self.claim(code,d,secret)[0],devices))
        self.assertEqual(sorted(results),[200,403])
        selected=devices[results.index(200)]
        self.assertEqual(self.claim(code,selected,secret)[0],200)
        self.assertEqual(self.claim(self.invite(),selected,secrets.token_hex(32))[0],403)
        h={'x-api-key':secret}
        self.assertEqual(self.request('GET',self.settings(deviceId=str(uuid.uuid4())),headers=h)[0],403)
        self.assertEqual(self.request('GET',self.settings(department='dev',deviceId=selected),headers=h)[0],403)

    def test_revoke_rejects_key_and_old_code(self):
        self.enable_cloud(); code=self.invite();key=secrets.token_hex(32)
        self.assertEqual(self.claim(code,self.device,key)[0],200)
        h={'x-api-key':key};self.assertEqual(self.request('GET',self.settings(),headers=h)[0],200)
        path='/admin/api/devices/'+self.device+'/revoke-cloud'
        self.assertEqual(self.portal('POST',path,{})[0],200)
        self.assertEqual(self.request('GET',self.settings(),headers=h)[0],401)
        self.assertEqual(self.claim(code,self.device,key)[0],403)
        self.assertEqual(self.claim(self.invite(),self.device,key)[0],200)

    def test_restart_preserves_url_pin_ack_and_logs(self):
        self.enable_cloud()
        key=secrets.token_hex(32);self.assertEqual(self.claim(self.invite(),self.device,key)[0],200)
        h={'x-api-key':key}
        settings=self.request('GET',self.settings(),headers=h)[1]
        self.assertEqual(settings['dashboardUrl'],'https://example.com/new')
        path='/api/v1/devices/'+self.device
        pin='/admin/api/devices/'+self.device+'/phone-pin'
        self.assertEqual(self.portal('PUT',pin,{'mode':'custom','pin':'042135'})[0],200)
        self.assertEqual(self.request('PUT',path+'/diagnostics',{'enabled':True},self.admin())[0],200)
        policy=self.request('GET',path+'/policy',headers=h)[1]
        self.assertEqual(policy['phonePinPolicy']['mode'],'custom')
        self.assertTrue(policy['diagnosticsEnabled'])
        for sub,data in [('/phone-pin/ack',{'version':policy['phonePinPolicyVersion']}),
                         ('/diagnostics/ack',{'enabled':True,'version':policy['diagnosticsPolicyVersion']}),
                         ('/diagnostics',{'events':[{'id':str(uuid.uuid4()),'kind':'LOADED','code':'','occurredAt':datetime.now(timezone.utc).isoformat()}]})]:
            self.assertEqual(self.request('POST',path+sub,data,h)[0],200)
        self.stop();self.start()
        self.assertEqual(self.request('GET',self.settings(),headers=h)[1]['databaseEpoch'],settings['databaseEpoch'])
        self.assertEqual(self.request('GET',path+'/policy',headers=h)[1],policy)
        state=self.request('GET',path+'/diagnostics',headers=self.admin())[1]
        self.assertTrue(state['tvAcknowledged']);self.assertEqual(state['items'][0]['kind'],'LOADED')
        self.assertEqual(self.portal('GET',pin)[1]['tvApplied'],True)

for name in dir(test_admin.AdminTests):
    if name.startswith('test_') and name not in CloudTests.__dict__:setattr(CloudTests,name,None)
