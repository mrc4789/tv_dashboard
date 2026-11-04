import unittest
import uuid
import test_admin

class DevicePolicyTests(test_admin.AdminTests):
    def register(self):
        self.assertEqual(self.request('GET',self.settings())[0],200)
        self.login()
    def policy(self,device=None,headers=None):
        return self.request('GET','/api/v1/devices/'+(device or self.device)+'/policy',headers=headers)
    def set_pin(self,body):
        return self.portal('PUT','/admin/api/devices/'+self.device+'/phone-pin',body)

    def test_invalid_url_does_not_block_pin(self):
        self.register()
        self.assertEqual(self.set_pin({'mode':'custom','pin':'012345'})[0],200)
        with self.server.connect() as c:
            c.execute("UPDATE settings SET dashboard_url='invalid' WHERE department_id='qa-qc'")
        self.assertEqual(self.request('GET',self.settings())[0],409)
        status,body,_=self.policy()
        self.assertEqual(status,200)
        self.assertEqual(body['phonePinPolicy']['mode'],'custom')
        self.assertEqual(body['phonePinPolicyVersion'],1)
        self.assertNotIn('dashboardUrl',body)
        self.assertNotIn('012345',str(body))

    def test_missing_settings_does_not_block_policy(self):
        self.register()
        with self.server.connect() as c:c.execute("DELETE FROM settings WHERE department_id='qa-qc'")
        status,body,headers=self.policy()
        self.assertEqual(status,200)
        self.assertEqual(body['phonePinPolicy'],{'mode':'random'})
        self.assertEqual(headers['Cache-Control'],'no-store')
        self.assertEqual(body['deviceId'],self.device)

    def test_auth_owner_scope_unknown_device(self):
        self.register()
        self.assertEqual(self.policy(headers={})[0],401)
        self.assertEqual(self.policy(headers={'x-api-key':'qa-test-key'})[0],403)
        self.assertEqual(self.policy(device=str(uuid.uuid4()))[0],404)
        with self.server.connect() as c:c.execute("UPDATE api_keys SET scope_json='[\"dev\"]' WHERE key_id='tv-all'")
        self.assertEqual(self.policy()[0],403)

    def test_versions_and_modes(self):
        self.register()
        self.set_pin({'mode':'custom','pin':'123456'})
        a=self.policy()[1]
        self.set_pin({'mode':'custom','pin':'654321'})
        b=self.policy()[1]
        self.assertEqual(b['phonePinPolicyVersion'],a['phonePinPolicyVersion']+1)
        self.assertNotEqual(a['phonePinPolicy']['hash'],b['phonePinPolicy']['hash'])
        self.set_pin({'mode':'random'})
        self.assertEqual(self.policy()[1]['phonePinPolicy'],{'mode':'random'})
        with self.server.connect() as c:c.execute('INSERT INTO diagnostic_policy(device_id,enabled,version) VALUES(?,1,4)',(self.device,))
        self.assertTrue(self.policy()[1]['diagnosticsEnabled'])
        self.assertEqual(self.policy()[1]['diagnosticsPolicyVersion'],4)

    def test_policy_does_not_modify_device(self):
        self.register()
        with self.server.connect() as c:before=dict(c.execute('SELECT * FROM devices WHERE device_id=?',(self.device,)).fetchone())
        self.policy()
        with self.server.connect() as c:after=dict(c.execute('SELECT * FROM devices WHERE device_id=?',(self.device,)).fetchone())
        self.assertEqual(before,after)


def load_tests(loader, tests, pattern):
    # Run only this module's new scenarios; inherited suites run in their own modules.
    return unittest.TestSuite(DevicePolicyTests(name) for name in DevicePolicyTests.__dict__ if name.startswith('test_'))
