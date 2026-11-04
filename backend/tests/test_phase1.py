import http.client
import json
import os
from pathlib import Path
import socket
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'));sys.path.insert(0,str(ROOT/'backend'))
from connection import load_connection,is_lan_ip
from run_service import credentials
import phone_bridge
import test_api

class CoreRegression(test_api.ApiTests):
    # Inherit the HTTP suite; run it once below by excluding inherited test names.
    def test_network_change_migrates_only_sample_urls(self):
        self.update('https://example.com/real')
        self.stop();self.cfg['dashboardBaseUrl']='http://192.168.2.99:8765';self.start()
        status,data,_=self.request('GET',self.settings());self.assertEqual(status,200)
        self.assertEqual(data['dashboardUrl'],'https://example.com/real')
        status,data,_=self.request('GET',self.settings(department='dev'))
        self.assertEqual(data['dashboardUrl'],'http://192.168.2.99:8765/sample-dashboard/dev')
        self.assertEqual(data['version'],2)
        self.stop();self.start()
        self.assertEqual(self.request('GET',self.settings(department='dev'))[1]['version'],2)

    def test_production_rejects_http_dashboard(self):
        self.server.config['developmentHttp']=False
        self.assertEqual(self.request('GET',self.settings())[0],409)
        self.assertEqual(self.update('http://example.com/insecure')[0],400)
        self.assertEqual(self.update('https://example.com/secure')[0],200)

    def test_failed_auth_is_rate_limited(self):
        self.server.config['keyRequestsPerMinute']=2
        for _ in range(2):self.assertEqual(self.request('GET',self.settings(),headers={'x-api-key':'bad'})[0],401)
        self.assertEqual(self.request('GET',self.settings(),headers={'x-api-key':'bad'})[0],429)

    def test_revoked_key_stays_revoked_after_restart(self):
        with self.server.connect() as db:db.execute("UPDATE api_keys SET active=0 WHERE key_id='tv-all'")
        self.stop();self.start()
        self.assertEqual(self.request('GET',self.settings())[0],401)

    def test_core_excludes_mock_controls(self):
        self.assertEqual(self.request('GET','/mock/control',headers=self.admin())[0],404)

    def test_all_four_settings(self):
        for dep in ('qa-qc','dev','project-teams','techgovernance'):
            status,data,_=self.request('GET',self.settings(department=dep))
            self.assertEqual(status,200);self.assertEqual(data['department']['id'],dep)

    def test_key_cli_create_scope_and_revoke(self):
        script=ROOT/'backend/manage_keys.py'
        result=subprocess.run([sys.executable,str(script),'--db',str(self.db),'create','qa-room','--departments','qa-qc'],capture_output=True,text=True,check=True)
        key=result.stdout.strip().splitlines()[-1]
        status,_,_=self.request('GET',self.settings(),headers={'x-api-key':key});self.assertEqual(status,200)
        status,_,_=self.request('GET',self.settings(department='dev'),headers={'x-api-key':key});self.assertEqual(status,403)
        with self.server.connect() as db:
            row=db.execute("SELECT key_hash FROM api_keys WHERE key_id='qa-room'").fetchone()
            self.assertNotEqual(row['key_hash'],key)
        subprocess.run([sys.executable,str(script),'--db',str(self.db),'revoke','qa-room'],capture_output=True,text=True,check=True)
        self.assertEqual(self.request('GET',self.settings(),headers={'x-api-key':key})[0],401)

    @unittest.skipUnless(shutil.which('openssl'),'HTTPS certificate fixture needs OpenSSL')
    def test_https_with_trusted_certificate(self):
        cert=Path(self.temp.name)/'cert.pem';key=Path(self.temp.name)/'key.pem'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,capture_output=True)
        self.stop()
        from config_service import ConfigService
        self.server=ConfigService(('127.0.0.1',0),self.db,ROOT/'backend/data/departments.seed.json',self.cfg,self.keys,'admin-test-key')
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.minimum_version=ssl.TLSVersion.TLSv1_2;ctx.load_cert_chain(cert,key)
        self.server.socket=ctx.wrap_socket(self.server.socket,server_side=True)
        self.thread=threading.Thread(target=lambda:self.server.serve_forever(poll_interval=.02),daemon=True);self.thread.start()
        client=http.client.HTTPSConnection('localhost',self.server.server_port,context=ssl.create_default_context(cafile=str(cert)),timeout=3)
        client.request('GET',self.settings(),headers={'x-api-key':'tv-test-key'})
        response=client.getresponse();self.assertEqual(response.status,200);response.read();client.close()
        client=http.client.HTTPSConnection('localhost',self.server.server_port,context=ssl.create_default_context(),timeout=3)
        try:
            with self.assertRaises(ssl.SSLCertVerificationError):client.request('GET','/health')
        finally:client.close()

# unittest discovers inherited methods; retain only the seven new tests on this subclass.
for name in dir(test_api.ApiTests):
    if name.startswith('test_'):setattr(CoreRegression,name,None)

class ConnectionTests(unittest.TestCase):
    def test_manual_ip_and_auto_detection(self):
        cfg=json.loads((ROOT/'connection.json').read_text())
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'config.json';cfg['pcLanIp']='192.168.1.77';p.write_text(json.dumps(cfg))
            self.assertEqual(load_connection(p)['pcLanIp'],'192.168.1.77')
            cfg['pcLanIp']='';p.write_text(json.dumps(cfg))
            with patch('connection.detect_lan_ip',return_value='172.16.20.30'):
                self.assertEqual(load_connection(p)['pcLanIp'],'172.16.20.30')

    def test_reject_emulator_public_and_duplicate_ports(self):
        cfg=json.loads((ROOT/'connection.json').read_text())
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'config.json'
            for ip in ('10.0.2.15','10.0.2.2','127.0.0.1','8.8.8.8','0.0.0.0'):
                cfg['pcLanIp']=ip;p.write_text(json.dumps(cfg))
                with self.assertRaises(ValueError):load_connection(p)
            cfg['pcLanIp']='192.168.1.77';cfg['apiPort']=cfg['phonePort'];p.write_text(json.dumps(cfg))
            with self.assertRaises(ValueError):load_connection(p)

    def test_10_can_be_a_real_lan_address(self):
        self.assertTrue(is_lan_ip('10.20.30.40'));self.assertFalse(is_lan_ip('169.254.1.1'))

    def test_generated_credentials_persist_and_are_distinct(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'credentials.json';first=credentials(p)
            self.assertEqual(first,credentials(p));self.assertNotEqual(first['tvKey'],first['adminToken'])
            self.assertGreaterEqual(len(first['tvKey']),32)
            if os.name!='nt':self.assertEqual(p.stat().st_mode & 0o777,0o600)

    def test_user_space_bridge_relays_tcp(self):
        upstream=socket.socket();upstream.bind(('127.0.0.1',0));upstream.listen(1)
        def echo():
            conn,_=upstream.accept()
            with conn:conn.sendall(conn.recv(1024))
        thread=threading.Thread(target=echo,daemon=True);thread.start()
        bridge=phone_bridge.BridgeServer(('127.0.0.1',0),upstream.getsockname()[1])
        t=threading.Thread(target=lambda:bridge.serve_forever(poll_interval=.02),daemon=True);t.start()
        try:
            with patch('phone_bridge.is_lan_ip',return_value=True):
                with socket.create_connection(bridge.server_address,timeout=3) as client:
                    client.sendall(b'bridge-test');self.assertEqual(client.recv(1024),b'bridge-test')
        finally:bridge.shutdown();bridge.server_close();t.join();upstream.close();thread.join(timeout=3)
