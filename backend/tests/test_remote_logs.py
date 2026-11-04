import unittest,uuid
from datetime import datetime,timezone
import sqlite3
import sys
from pathlib import Path
import test_api
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
import remote_logs
class RemoteLogTests(unittest.TestCase):
 setUp=test_api.ApiTests.setUp
 tearDown=test_api.ApiTests.tearDown
 start=test_api.ApiTests.start
 stop=test_api.ApiTests.stop
 request=test_api.ApiTests.request
 settings=test_api.ApiTests.settings
 admin=test_api.ApiTests.admin
 def path(self):return '/api/v1/devices/'+self.device+'/diagnostics'
 def register(self):self.request('GET',self.settings());self.server.config['deviceRequestsPerMinute']=1000
 def enable(self):self.register();return self.request('PUT',self.path(),{'enabled':True},self.admin())
 def event(self):return {'id':str(uuid.uuid4()),'kind':'HTTP_SERVER','code':'403','occurredAt':datetime.now(timezone.utc).isoformat()}
 def test_opt_in_and_settings(self):
  self.register();self.assertEqual(self.request('POST',self.path(),{'events':[self.event()]})[0],403)
  enabled=self.enable();self.assertEqual(enabled[0],200)
  self.assertTrue(enabled[1]['enabled']);self.assertFalse(enabled[1]['tvAcknowledged'])
  settings=self.request('GET',self.settings())[1]
  self.assertTrue(settings['diagnosticsEnabled']);self.assertEqual(settings['diagnosticsPolicyVersion'],1)
  self.assertFalse(self.request('GET',self.path(),headers=self.admin())[1]['tvAcknowledged'])
  ack=self.request('POST',self.path()+'/ack',{'enabled':True,'version':settings['diagnosticsPolicyVersion']})
  self.assertEqual(ack[0],200);self.assertTrue(self.request('GET',self.path(),headers=self.admin())[1]['tvAcknowledged'])
 def test_admin_auth(self):
  self.register()
  for method in ['GET','PUT','DELETE']:self.assertIn(self.request(method,self.path(),{'enabled':True} if method=='PUT' else None)[0],(401,403))
 def test_dedupe_and_other_key(self):
  self.enable();event=self.event()
  for _ in range(2):self.assertEqual(self.request('POST',self.path(),{'events':[event]})[0],200)
  item=self.request('GET',self.path(),headers=self.admin())[1]['items'][0]
  self.assertEqual(item['kind'],'HTTP_SERVER');self.assertTrue(item['occurred_at']);self.assertTrue(item['received_at'])
  self.assertEqual(len(self.request('GET',self.path(),headers=self.admin())[1]['items']),1)
  self.assertEqual(self.request('POST',self.path(),{'events':[self.event()]},{'x-api-key':'qa-test-key'})[0],403)
 def test_reject_secrets_and_bad_fields(self):
  self.enable()
  for event in [dict(self.event(),message='secret'),dict(self.event(),code='https://secret'),dict(self.event(),kind='OTHER'),dict(self.event(),id='bad')]:
   self.assertEqual(self.request('POST',self.path(),{'events':[event]})[0],400)
  self.assertEqual(self.request('POST',self.path(),{'events':[self.event() for _ in range(21)]})[0],400)
  bad=dict(self.event(),occurredAt='https://secret')
  self.assertEqual(self.request('POST',self.path(),{'events':[bad]})[0],400)
  self.assertEqual(self.request('PUT',self.path(),{'enabled':'true'},self.admin())[0],400)
 def test_disable_delete_and_retention(self):
  self.enable();self.request('POST',self.path(),{'events':[self.event()]})
  with self.server.connect() as c:c.execute("UPDATE diagnostic_logs SET received_at='2000-01-01T00:00:00Z'")
  self.assertEqual(self.request('GET',self.path(),headers=self.admin())[1]['items'],[])
  self.request('POST',self.path(),{'events':[self.event()]})
  self.assertEqual(self.request('DELETE',self.path(),headers=self.admin())[0],200)
  self.assertEqual(self.request('GET',self.path(),headers=self.admin())[1]['items'],[])
  disabled=self.request('PUT',self.path(),{'enabled':False},self.admin())[1]
  self.assertFalse(disabled['enabled']);self.assertFalse(disabled['tvAcknowledged'])
  self.assertEqual(self.request('POST',self.path(),{'events':[self.event()]})[0],403)
 def test_per_device_bound(self):
  self.enable();self.server.config['keyRequestsPerMinute']=1000
  for _ in range(14):self.assertEqual(self.request('POST',self.path(),{'events':[self.event() for _ in range(20)]})[0],200)
  with self.server.connect() as c:self.assertEqual(c.execute('SELECT COUNT(*) FROM diagnostic_logs').fetchone()[0],250)
 def test_ack_requires_latest_policy_and_correct_key(self):
  self.enable()
  settings=self.request('GET',self.settings())[1]
  self.assertEqual(self.request('POST',self.path()+'/ack',{'enabled':True,'version':settings['diagnosticsPolicyVersion']-1})[0],409)
  self.assertEqual(self.request('POST',self.path()+'/ack',{'enabled':True,'version':settings['diagnosticsPolicyVersion']},{'x-api-key':'qa-test-key'})[0],403)
 def test_existing_database_migration_preserves_logs(self):
  db=sqlite3.connect(':memory:');db.row_factory=sqlite3.Row
  db.execute('CREATE TABLE diagnostic_policy(device_id TEXT PRIMARY KEY,enabled INTEGER NOT NULL)')
  db.execute('INSERT INTO diagnostic_policy VALUES(?,?)',(self.device,1))
  db.execute('CREATE TABLE diagnostic_logs(device_id TEXT NOT NULL,event_id TEXT NOT NULL,kind TEXT NOT NULL,code TEXT NOT NULL,received_at TEXT NOT NULL,PRIMARY KEY(device_id,event_id))')
  stamp=datetime.now(timezone.utc).isoformat()
  db.execute('INSERT INTO diagnostic_logs VALUES(?,?,?,?,?)',(self.device,str(uuid.uuid4()),'NETWORK','-2',stamp))
  remote_logs.init(db)
  policy=db.execute('SELECT enabled,version FROM diagnostic_policy WHERE device_id=?',(self.device,)).fetchone()
  log=db.execute('SELECT occurred_at,received_at FROM diagnostic_logs WHERE device_id=?',(self.device,)).fetchone()
  self.assertEqual((policy['enabled'],policy['version']),(1,1))
  self.assertEqual(log['occurred_at'],stamp);self.assertEqual(log['received_at'],stamp)
  self.assertTrue(db.execute("SELECT 1 FROM sqlite_master WHERE name='diagnostic_ack'").fetchone())
  db.close()

 def test_current_state_after_opt_in(self):
  self.enable()
  for kind in ('LOADED','LOAD_ERROR'):
   event=self.event();event['kind']=kind
   self.assertEqual(self.request('POST',self.path(),{'events':[event]})[0],200)
  items=self.request('GET',self.path(),headers=self.admin())[1]['items']
  self.assertTrue({'LOADED','LOAD_ERROR'}.issubset({item['kind'] for item in items}))
