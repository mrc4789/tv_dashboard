"""Transport contract fixture backed by actual SQLite; no external provider credentials."""
import io,json,sqlite3,tempfile,unittest,uuid
from pathlib import Path
from unittest.mock import patch
import test_api
from turso_store import connect,endpoint
from config_service import ConfigService
from cloud_pairing import issue,redeem

class HranaFixture:
    def __init__(self,path): self.path=path;self.sessions={}
    def open(self,request,timeout):
        data=json.loads(request.data);baton=data['baton'] or str(uuid.uuid4())
        if not data['baton']:
            self.sessions[baton]=sqlite3.connect(self.path,isolation_level=None)
        conn=self.sessions[baton];out=[]
        for item in data['requests']:
            if item['type']=='close':
                conn.close();del self.sessions[baton];baton=None;res={'type':'close'}
            else:
                stmt=item['stmt'];params=[(int(v['value']) if v['type']=='integer' else v.get('value')) for v in stmt['args']]
                cursor=conn.execute(stmt['sql'],params)
                cols=[{'name':x[0]} for x in cursor.description or []]
                def cell(v):
                    if v is None:return {'type':'null'}
                    return {'type':'integer' if isinstance(v,int) else 'text','value':str(v)}
                res={'type':'execute','result':{'cols':cols,'rows':[[cell(v) for v in row] for row in cursor]}}
            out.append({'type':'ok','response':res})
        return io.BytesIO(json.dumps({'baton':baton,'results':out}).encode())

class StorageTests(unittest.TestCase):
    def test_remote_store_survives_service_replacement_and_rolls_back(self):
        url='libsql://db-test.turso.io';cfg={'tursoUrl':url,'tursoToken':'test','dashboardBaseUrl':'https://demo.test'}
        with tempfile.TemporaryDirectory() as tmp:
            fixture=HranaFixture(Path(tmp)/'db')
            with patch('turso_store.build_opener',return_value=fixture):
                def new():return ConfigService(('127.0.0.1',0),':remote:',test_api.ROOT/'data/departments.seed.json',cfg,[],'token')
                one=new()
                try:
                    epoch=one.database_epoch;code=issue(one,'dev')['code']
                    with self.assertRaises(ValueError):
                        with one.connect() as db:
                            db.execute("UPDATE settings SET version=99");raise ValueError()
                finally:one.server_close()
                two=new()
                try:
                    self.assertEqual(epoch,two.database_epoch)
                    self.assertEqual(redeem(two,{'deviceKey':'a'*64,'deviceId':str(uuid.uuid4()),'code':code})['department'],'dev')
                    with two.connect() as db:self.assertEqual(db.execute('SELECT version FROM settings').fetchone()[0],1)
                finally:two.server_close()
                self.assertFalse(fixture.sessions)
    def test_fail_closed(self):
        import sys
        sys.path.insert(0,str(test_api.ROOT))
        from run_render import storage
        for env in ({},{'TURSO_DATABASE_URL':'libsql://example.turso.io'},{'TURSO_DATABASE_URL':'http://evil.test','TURSO_AUTH_TOKEN':'test'},{'TV_DATA_DIR':'relative'}):
            with self.assertRaises(ValueError):storage(env,{})
