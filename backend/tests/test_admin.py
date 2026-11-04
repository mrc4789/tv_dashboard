import concurrent.futures
from http.cookies import SimpleCookie
import http.client
import json
from pathlib import Path
import time
import shutil
import ssl
import subprocess
import threading
import unittest
import uuid
import test_api
from admin_portal import provision_admin

class AdminTests(test_api.ApiTests):
    def setUp(self):
        super().setUp()
        self.jar={};self.csrf='';self.password='A-test-password-123456'
        self.bootstrap=Path(self.temp.name)/'admin-bootstrap.json'
        self.bootstrap.write_text(json.dumps({'username':'mentor','password':self.password}))
        provision_admin(self.server,self.bootstrap)
    def portal(self,method,path,body=None,headers=None,jar=None):
        cookies=self.jar if jar is None else jar
        hdr={'Cookie':'; '.join(k+'='+v for k,v in cookies.items())}
        if method in ('POST','PUT'):
            hdr['Origin']=f'http://127.0.0.1:{self.server.server_port}';hdr['X-CSRF-Token']=self.csrf
        hdr.update(headers or {})
        raw=None
        if body is not None:raw=json.dumps(body).encode();hdr['Content-Type']='application/json'
        client=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        client.request(method,path,raw,hdr);response=client.getresponse();content=response.read();status=response.status;response_headers=response.getheaders();client.close()
        for name,value in response_headers:
            if name.lower()=='set-cookie':
                parsed=SimpleCookie();parsed.load(value)
                for key,m in parsed.items():
                    if m['max-age']=='0':cookies.pop(key,None)
                    else:cookies[key]=m.value
        try:data=json.loads(content)
        except ValueError:data=content
        return status,data,response_headers
    def login(self,password=None):
        # A fresh login page has an anonymous CSRF cookie; authenticated pages use session CSRF.
        self.jar.pop('tv_admin_session',None)
        status,data,_=self.portal('GET','/admin/api/session');self.assertEqual(status,200);self.csrf=data['csrfToken']
        status,data,headers=self.portal('POST','/admin/api/login',{'username':'mentor','password':password or self.password})
        if status==200:self.csrf=data['csrfToken']
        return status,data,headers
    def save(self,url='https://example.com/qa',version=1,**kwargs):
        return self.portal('PUT','/api/v1/departments/qa-qc/settings',{'dashboardUrl':url,'expectedVersion':version},**kwargs)
    def test_admin_assets_and_csp(self):
        for path in ('/admin','/admin/','/admin/app.js','/admin/style.css'):
            status,data,headers=self.portal('GET',path);self.assertEqual(status,200);self.assertTrue(data)
            self.assertIn("frame-ancestors 'none'",dict(headers)['Content-Security-Policy'])
        self.assertEqual(self.portal('GET','/admin/../data/admin-bootstrap.json')[0],404)
    def test_anonymous_and_tv_key_cannot_read_admin_list(self):
        for headers in ({},{'x-api-key':'tv-test-key'},{'Authorization':'Bearer admin-test-key'}):
            self.assertEqual(self.portal('GET','/admin/api/departments',headers=headers)[0],401)
    def test_login_password_and_session_hash_cookie_flags(self):
        status,data,headers=self.login();self.assertEqual(status,200);self.assertEqual(data['username'],'mentor')
        self.assertNotIn('password',data)
        cookies=[v for k,v in headers if k.lower()=='set-cookie' and v.startswith('tv_admin_session=')]
        self.assertEqual(len(cookies),1);self.assertIn('HttpOnly',cookies[0]);self.assertIn('SameSite=Strict',cookies[0]);self.assertNotIn('; Secure',cookies[0])
        with self.server.connect() as db:
            user=db.execute('SELECT * FROM admin_users').fetchone();session=db.execute('SELECT * FROM admin_sessions').fetchone()
        self.assertNotEqual(user['password_hash'],self.password);self.assertNotEqual(session['token_hash'],self.jar['tv_admin_session'])
        self.assertEqual(self.portal('GET','/admin/api/session')[1]['authenticated'],True)
    def test_cloud_origin_secure_cookie_and_csrf(self):
        self.server.config['publicOrigin']='https://factory-demo.koyeb.app'
        host={'Host':'factory-demo.koyeb.app'}
        status,data,_=self.portal('GET','/admin/api/session',headers=host)
        self.assertEqual(status,200);self.csrf=data['csrfToken']
        headers={**host,'Origin':'https://factory-demo.koyeb.app'}
        status,data,response=self.portal('POST','/admin/api/login',{'username':'mentor','password':self.password},headers=headers)
        self.assertEqual(status,200);self.csrf=data['csrfToken']
        self.assertTrue(any('; Secure' in v for k,v in response if k.lower()=='set-cookie'))
        self.assertEqual(self.portal('PUT','/api/v1/departments/qa-qc/settings',
                                    {'dashboardUrl':'https://example.com/ok','expectedVersion':1},headers=headers)[0],200)
        self.assertEqual(self.portal('POST','/admin/api/logout',{},headers={**host,'Origin':'http://factory-demo.koyeb.app'})[0],403)
        self.assertEqual(self.portal('POST','/admin/api/logout',{},headers={**headers,'Host':'evil.example'})[0],403)
    def test_wrong_password(self):
        status,data,_=self.login('wrong');self.assertEqual(status,401);self.assertEqual(data['error']['code'],'INVALID_CREDENTIALS');self.assertNotIn('tv_admin_session',self.jar)
    def test_login_requires_same_origin_and_csrf(self):
        _,data,_=self.portal('GET','/admin/api/session');self.csrf=data['csrfToken']
        body={'username':'mentor','password':self.password}
        for headers in ({'X-CSRF-Token':''},{'Origin':'https://evil.example'},{'Origin':'null'}):
            self.assertEqual(self.portal('POST','/admin/api/login',body,headers)[0],403)
    def test_login_rate_limit(self):
        for _ in range(5):self.assertEqual(self.login('wrong')[0],401)
        status,data,headers=self.login('wrong');self.assertEqual(status,429);self.assertIn('Retry-After',dict(headers))
    def test_cookie_save_audit_actor_and_tv_readback(self):
        self.login();status,data,_=self.save();self.assertEqual(status,200);self.assertEqual(data['version'],2)
        self.assertEqual(self.request('GET',self.settings())[1]['dashboardUrl'],'https://example.com/qa')
        audit=self.portal('GET','/admin/api/audit')[1]['items'];self.assertEqual(audit[0]['changed_by'],'mentor')
        deps=self.portal('GET','/admin/api/departments')[1]['items'];qa=next(d for d in deps if d['id']=='qa-qc');self.assertEqual(qa['updatedBy'],'mentor');self.assertEqual(len(deps),4)
    def test_mutation_rejects_csrf_and_cross_origin(self):
        self.login()
        for headers in ({'X-CSRF-Token':'bad'},{'Origin':'http://evil.example'}):self.assertEqual(self.save(headers=headers)[0],403)
        self.assertEqual(self.request('GET',self.settings())[1]['version'],1)
    def test_same_url_does_not_increase_version_or_audit(self):
        self.login();url=self.request('GET',self.settings())[1]['dashboardUrl']
        self.assertEqual(self.save(url)[1]['version'],1);self.assertEqual(self.portal('GET','/admin/api/audit')[1]['total'],0)
    def test_stale_version_and_concurrent_sessions(self):
        self.login();old=dict(self.jar);csrf=self.csrf
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            statuses=list(pool.map(lambda n:self.save('https://example.com/'+str(n),jar=dict(old),headers={'X-CSRF-Token':csrf})[0],[1,2]))
        self.assertEqual(sorted(statuses),[200,409]);self.assertEqual(self.portal('GET','/admin/api/audit')[1]['total'],1)
    def test_validation_is_syntax_only_no_heartbeat(self):
        self.login();status,data,_=self.portal('POST','/admin/api/validate-url',{'dashboardUrl':'https://unreachable.invalid/path'})
        self.assertEqual(status,200);self.assertIn('Chưa kiểm tra mạng',data['message'])
        self.assertEqual(self.portal('POST','/admin/api/validate-url',{'dashboardUrl':'javascript:alert(1)'})[0],400)
        with self.server.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM devices').fetchone()[0],0)
    def test_logout_revokes_session(self):
        self.login();old=dict(self.jar);self.assertEqual(self.portal('POST','/admin/api/logout',{})[0],200)
        self.assertNotIn('tv_admin_session',self.jar);self.assertEqual(self.portal('GET','/admin/api/departments',jar=old)[0],401)
    def test_expired_session_denied(self):
        self.login()
        with self.server.connect() as db:db.execute('UPDATE admin_sessions SET expires_at=?',(int(time.time())-1,))
        self.assertEqual(self.portal('GET','/admin/api/departments')[0],401)
        self.assertEqual(self.portal('GET','/admin/api/session')[1]['authenticated'],False)
    def test_password_change_revokes_all_and_persists(self):
        self.login();old=dict(self.jar);new='A-new-long-password-987654'
        self.assertEqual(self.portal('POST','/admin/api/password',{'currentPassword':self.password,'newPassword':new})[0],200)
        self.assertEqual(self.portal('GET','/admin/api/departments',jar=old)[0],401)
        self.assertEqual(self.login()[0],401);self.assertEqual(self.login(new)[0],200)
        self.stop();self.start();self.assertFalse(provision_admin(self.server,self.bootstrap));self.assertEqual(self.login(new)[0],200)
    def test_wrong_old_password_or_short_new_password(self):
        self.login()
        self.assertEqual(self.portal('POST','/admin/api/password',{'currentPassword':'wrong','newPassword':'Long-new-password-123'})[0],401)
        self.assertEqual(self.portal('POST','/admin/api/password',{'currentPassword':self.password,'newPassword':'short'})[0],400)
        self.assertEqual(self.portal('GET','/admin/api/departments')[0],200)
    def test_audit_filter_pagination(self):
        self.login();self.save('https://example.com/1',1);self.save('https://example.com/2',2)
        status,data,_=self.portal('GET','/admin/api/audit?department=qa-qc&pageSize=1&page=2');self.assertEqual(status,200);self.assertEqual(data['total'],2);self.assertEqual(len(data['items']),1)
        self.assertEqual(self.portal('GET','/admin/api/audit?department=dev')[1]['total'],0)
        self.assertEqual(self.portal('GET','/admin/api/audit?pageSize=1000')[0],400)
    def test_admin_reads_do_not_create_fake_tv(self):
        self.login();self.portal('GET','/admin/api/departments');self.portal('GET','/admin/api/audit')
        with self.server.connect() as db:self.assertEqual(db.execute('SELECT COUNT(*) FROM devices').fetchone()[0],0)
    def test_provision_account_does_not_reset_existing_password(self):
        self.bootstrap.write_text(json.dumps({'username':'admin','password':'different-new-bootstrap-password'}))
        self.assertFalse(provision_admin(self.server,self.bootstrap));self.assertEqual(self.login()[0],200)
    def test_deactivated_admin_cannot_keep_session(self):
        self.login()
        with self.server.connect() as db:db.execute('UPDATE admin_users SET active=0')
        self.assertEqual(self.portal('GET','/admin/api/departments')[0],401)
    def test_session_survives_restart_until_expiry(self):
        self.login();self.stop();self.start()
        self.assertEqual(self.portal('GET','/admin/api/session')[1]['username'],'mentor')
        self.assertEqual(self.portal('GET','/admin/api/departments')[0],200)

    @unittest.skipUnless(shutil.which('openssl'),'HTTPS certificate fixture needs OpenSSL')
    def test_https_sets_secure_session_cookie(self):
        cert=Path(self.temp.name)/'cert.pem';key=Path(self.temp.name)/'key.pem'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,capture_output=True)
        self.stop()
        from config_service import ConfigService
        self.server=ConfigService(('127.0.0.1',0),self.db,Path(__file__).resolve().parents[1]/'data/departments.seed.json',self.cfg,self.keys,'admin-test-key')
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,key)
        self.server.socket=context.wrap_socket(self.server.socket,server_side=True)
        self.thread=threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True);self.thread.start()
        client_context=ssl.create_default_context(cafile=str(cert))
        client=http.client.HTTPSConnection('localhost',self.server.server_port,context=client_context,timeout=5)
        client.request('GET','/admin/api/session');response=client.getresponse();data=json.loads(response.read());login_cookie=response.getheader('Set-Cookie');client.close()
        self.assertIn('; Secure',login_cookie)
        client=http.client.HTTPSConnection('localhost',self.server.server_port,context=client_context,timeout=5)
        raw=json.dumps({'username':'mentor','password':self.password})
        client.request('POST','/admin/api/login',raw,{'Content-Type':'application/json','Cookie':login_cookie.split(';')[0],'Origin':f'https://localhost:{self.server.server_port}','X-CSRF-Token':data['csrfToken']})
        response=client.getresponse();self.assertEqual(response.status,200);response.read()
        cookies=[v for k,v in response.getheaders() if k.lower()=='set-cookie' and v.startswith('tv_admin_session=')]
        self.assertIn('; Secure',cookies[0]);self.assertIn('HttpOnly',cookies[0]);client.close()

for name in dir(test_api.ApiTests):
    if name.startswith('test_'):setattr(AdminTests,name,None)
