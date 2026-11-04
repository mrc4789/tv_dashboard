import unittest,uuid
from test_api import ApiTests
class HardeningTests(unittest.TestCase):
 setUp=ApiTests.setUp
 tearDown=ApiTests.tearDown
 start=ApiTests.start
 stop=ApiTests.stop
 request=ApiTests.request
 settings=ApiTests.settings
 def test_other_key_cannot_take_device(self):
  self.assertEqual(self.request('GET',self.settings(name='Original'))[0],200)
  status,body,_=self.request('GET',self.settings(name='Replaced'),headers={'x-api-key':'qa-test-key'})
  self.assertEqual(status,403);self.assertNotIn('phonePinPolicy',body)
  with self.server.connect() as db:self.assertEqual(db.execute('SELECT name FROM devices WHERE device_id=?',(self.device,)).fetchone()[0],'Original')
 def test_binding_survives_restart(self):
  self.request('GET',self.settings());self.stop();self.start()
  self.assertEqual(self.request('GET',self.settings(),headers={'x-api-key':'qa-test-key'})[0],403)
 def test_lan_key_bound_to_one_uuid(self):
  from config_service import digest
  with self.server.connect() as db:
   db.execute('INSERT INTO api_keys VALUES(?,?,NULL,1)',('lan-unit',digest('one-tv-key')))
   db.execute('INSERT INTO device_bindings VALUES(?,?)',(self.device,'lan-unit'))
  self.assertEqual(self.request('GET',self.settings(),headers={'x-api-key':'one-tv-key'})[0],200)
  self.assertEqual(self.request('GET',self.settings(deviceId=str(uuid.uuid4())),headers={'x-api-key':'one-tv-key'})[0],403)
