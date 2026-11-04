import hashlib
import json
import test_admin

class PhonePinTests(test_admin.AdminTests):
    def endpoint(self):return '/admin/api/devices/'+self.device+'/phone-pin'
    def register(self):self.assertEqual(self.request('GET',self.settings())[0],200)
    def test_pin_auth_and_csrf(self):
        self.register()
        self.assertEqual(self.portal('PUT',self.endpoint(),{'mode':'custom','pin':'012345'})[0],401)
        self.login()
        self.assertEqual(self.portal('PUT',self.endpoint(),{'mode':'custom','pin':'012345'},headers={'X-CSRF-Token':'bad'})[0],403)
        for pin in ['1234','123456789','abcdef','１２３４５６',123456]:
            self.assertEqual(self.portal('PUT',self.endpoint(),{'mode':'custom','pin':pin})[0],400)
    def test_pin_lifecycle_and_isolation(self):
        self.register();self.login()
        seq=self.server.event_sequence['qa-qc']
        self.assertEqual(self.portal('PUT',self.endpoint(),{'mode':'custom','pin':'012345'})[0],200)
        self.assertGreater(self.server.event_sequence['qa-qc'],seq)
        policy=self.request('GET',self.settings())[1]['phonePinPolicy']
        settings=self.request('GET',self.settings())[1]
        self.assertEqual(settings['phonePinPolicyVersion'],1)
        self.assertFalse(self.portal('GET',self.endpoint())[1]['tvApplied'])
        self.assertEqual(self.request('POST','/api/v1/devices/'+self.device+'/phone-pin/ack',{'version':settings['phonePinPolicyVersion']})[0],200)
        self.assertTrue(self.portal('GET',self.endpoint())[1]['tvApplied'])
        self.assertEqual(self.request('POST','/api/v1/devices/'+self.device+'/phone-pin/ack',{'version':0})[0],409)
        self.assertEqual(policy['hash'],hashlib.pbkdf2_hmac('sha256',b'012345',bytes.fromhex(policy['salt']),100000).hex())
        public=self.portal('GET',self.endpoint())[1]
        self.assertEqual(public['mode'],'custom');self.assertNotIn('hash',public)
        with self.server.connect() as db:
            stored=db.execute('SELECT * FROM device_pins').fetchone()
            self.assertNotIn('012345',stored['policy_json']);self.assertEqual(stored['updated_by'],'mentor')
        import uuid
        self.assertEqual(self.request('GET',self.settings(deviceId=str(uuid.uuid4())))[1]['phonePinPolicy'],{'mode':'random'})
        self.assertEqual(self.portal('PUT',self.endpoint(),{'mode':'custom','pin':'87654321'})[0],200)
        changed=self.request('GET',self.settings())[1]
        self.assertNotEqual(changed['phonePinPolicy']['hash'],policy['hash'])
        self.assertEqual(changed['phonePinPolicyVersion'],2)
        self.assertFalse(self.portal('GET',self.endpoint())[1]['tvApplied'])
        self.stop();self.start()
        self.assertEqual(self.request('GET',self.settings())[1]['phonePinPolicy']['mode'],'custom')
        self.login()
        self.assertEqual(self.portal('PUT',self.endpoint(),{'mode':'random'})[0],200)
        random_policy=self.request('GET',self.settings())[1]
        self.assertEqual(random_policy['phonePinPolicy'],{'mode':'random'})
        self.assertEqual(random_policy['phonePinPolicyVersion'],3)
        self.assertFalse(self.portal('GET',self.endpoint())[1]['tvApplied'])
        self.assertEqual(self.request('POST','/api/v1/devices/'+self.device+'/phone-pin/ack',{'version':3})[0],200)
        self.assertTrue(self.portal('GET',self.endpoint())[1]['tvApplied'])
