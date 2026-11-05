"""Small synchronous Hrana HTTP adapter for this service's parameterized SQLite queries."""
from contextlib import contextmanager
import json
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def endpoint(url):
    u = urlsplit(url)
    if (u.scheme not in ('https', 'libsql') or not u.hostname or not u.hostname.endswith('.turso.io')
        or u.username is not None or u.password is not None or u.port not in (None,443)
        or u.query or u.fragment or u.path not in ('','/')):
        raise ValueError('TURSO_DATABASE_URL phải là địa chỉ gốc libsql:// hoặc https:// của DB Turso.')
    return 'https://' + u.hostname + '/v2/pipeline'


class Row(dict):
    def __getitem__(self, key):
        return tuple(self.values())[key] if isinstance(key,int) else super().__getitem__(key)


class Cursor(list):
    def fetchone(self): return self[0] if self else None
    def fetchall(self): return list(self)


def arg(v):
    if v is None: return {'type':'null'}
    if isinstance(v,int): return {'type':'integer','value':str(v)}
    if isinstance(v,str): return {'type':'text','value':v}
    raise TypeError('Unsupported SQL parameter')


def statement(sql, params=()):
    return {'type':'execute','stmt':{'sql':sql,'args':[arg(x) for x in params],'want_rows':True}}


def rows(result):
    names = [c['name'] for c in result['cols']]
    def value(v):
        if v['type']=='integer': return int(v['value'])
        return v.get('value')
    return Cursor(Row(zip(names,map(value,r))) for r in result['rows'])


class Connection:
    def __init__(self,url,token):
        self.url=endpoint(url);self.token=token;self.baton=None
        self.opener=build_opener(NoRedirect())

    def rpc(self, requests):
        data=json.dumps({'baton':self.baton,'requests':requests}).encode()
        req=Request(self.url,data,{'Content-Type':'application/json','Authorization':'Bearer '+self.token})
        try:
            with self.opener.open(req,timeout=12) as res:
                out=json.load(res)
        except Exception:
            raise RuntimeError('Không kết nối được DB Turso. Kiểm tra URL/token/mạng.') from None
        self.baton=out.get('baton')
        if out.get('base_url'): self.url=endpoint(out['base_url'])
        results=out['results']
        if len(results)!=len(requests) or any(r['type']!='ok' for r in results):
            raise RuntimeError('DB Turso từ chối giao dịch; kiểm tra trạng thái và quyền DB.')
        return [r['response'].get('result') for r in results]

    def run(self, queries):
        first=queries[0]['stmt']['sql'].lstrip().split(None,1)[0].upper()
        if self.baton: return self.rpc(queries)
        if first in ('SELECT','PRAGMA') and len(queries)==1:
            return self.rpc(queries+[{'type':'close'}])[:-1]
        if first=='BEGIN': return self.rpc(queries)
        return self.rpc([statement('BEGIN IMMEDIATE')]+queries)[1:]

    def execute(self,sql,params=()): return rows(self.run([statement(sql,params)])[0])
    def executemany(self,sql,params):
        queries=[statement(sql,p) for p in params]
        if queries:self.run(queries)
    def executescript(self,sql):
        self.run([statement(q.strip()) for q in sql.split(';') if q.strip()])

    def finish(self,commit):
        if self.baton:self.rpc([statement('COMMIT' if commit else 'ROLLBACK'),{'type':'close'}])


@contextmanager
def connect(url,token):
    db=Connection(url,token)
    try:yield db
    except BaseException:
        try:db.finish(False)
        except Exception:pass
        raise
    else:db.finish(True)
