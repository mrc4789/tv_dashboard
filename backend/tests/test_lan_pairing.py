import hashlib
import hmac
import http.client
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server'))
from config_service import ConfigService
from lan_pairing import LanPairing, private_ipv4, PROTOCOL

class LanPairingTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        cfg=dict(developmentHttp=True,dashboardBaseUrl='http://127.0.0.1:8765',pollSeconds=300,
                 offlineSeconds=720,keyRequestsPerMinute=1000,deviceRequestsPerMinute=1000,pairingDepartment='dev')
        self.server=ConfigService(('127.0.0.1',0),Path(self.tmp.name)/'db',ROOT/'data/departments.seed.json',cfg,[('main','test-existing-key',None)],'admin-only-secret')
        self.pair=LanPairing(self.server,600,port=0,allow_loopback=True)
        self.server.lan_pairing=self.pair
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.tmp.cleanup()
    def ad(self,peer='127.0.0.1',**extra):
        return self.pair.advertisement(dict(protocol=PROTOCOL,nonce='a'*32,**extra),peer)
    def post(self,body,headers=None):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port)
        c.request('POST','/api/v1/pairing/enroll',json.dumps(body),headers or {'Content-Type':'application/json'})
        r=c.getresponse();status=r.status;data=json.loads(r.read());c.close();return status,data
    def payload(self):return dict(ticket=self.ad()['ticket'],deviceId=str(uuid.uuid4()))
    def test_closed_enrollment_still_discovers_authenticated_server(self):
        self.pair.close()
        self.pair = LanPairing(self.server, 0, port=0, allow_loopback=True)
        self.server.lan_pairing = self.pair
        fingerprint = hashlib.sha256(b'test-existing-key').hexdigest()[:32]
        ad = self.ad(fingerprint=fingerprint)
        self.assertFalse(ad['open'])
        self.assertNotIn('ticket', ad)
        message = f"{ad['nonce']}|{ad['host']}|{ad['scheme']}|{ad['port']}"
        expected = hmac.new(hashlib.sha256(b'test-existing-key').digest(), message.encode(), hashlib.sha256).hexdigest()
        self.assertEqual(ad['proof'], expected)
        self.assertNotIn('proof', self.ad(fingerprint='0'*32))
        status, _ = self.post(dict(ticket='invalid', deviceId=str(uuid.uuid4())))
        self.assertEqual(status, 403)

    def test_private_ranges_only(self):
        for ip in ['10.1.2.3','172.16.0.2','192.168.50.20']:self.assertTrue(private_ipv4(ip))
        for ip in ['8.8.8.8','127.0.0.1','169.254.1.2','::1','172.32.0.1']:self.assertFalse(private_ipv4(ip))
    def test_discovery_uses_real_udp_no_secrets(self):
        with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as s:
            s.settimeout(2);s.sendto(json.dumps(dict(protocol=PROTOCOL,nonce='b'*32)).encode(),('127.0.0.1',self.pair.port))
            raw,_=s.recvfrom(2048)
        data=json.loads(raw);self.assertEqual(data['nonce'],'b'*32)
        self.assertNotIn('apiKey',data);self.assertNotIn('test-existing-key',raw.decode())
    def test_discovery_rejects_bad_protocol_nonce_public_peer(self):
        self.assertIsNone(self.pair.advertisement({},'127.0.0.1'))
        self.assertIsNone(self.pair.advertisement(dict(protocol=PROTOCOL,nonce='bad'),'127.0.0.1'))
        self.assertIsNone(self.ad('8.8.8.8'))
    def test_pin_provisioning_issues_unique_readonly_key_hash(self):
        code,data=self.post(self.payload());self.assertEqual(code,200)
        self.assertEqual(data['department'],'dev');self.assertNotEqual(data['apiKey'],'test-existing-key')
        with self.server.connect() as db:
            row=db.execute('SELECT * FROM api_keys WHERE key_hash=?',(hashlib.sha256(data['apiKey'].encode()).hexdigest(),)).fetchone()
            self.assertEqual(set(json.loads(row['scope_json'])),{'dev','qa-qc','project-teams','techgovernance'})
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port)
        c.request('GET','/api/v1/devices',headers={'Authorization':'Bearer '+data['apiKey']})
        r=c.getresponse();self.assertEqual(r.status,403);r.read();c.close()
    def test_ticket_cannot_replay(self):
        p=self.payload();self.assertEqual(self.post(p)[0],200);self.assertEqual(self.post(p)[0],403)
    def test_ticket_bound_to_requesting_ip(self):
        p=dict(ticket=self.ad('192.168.1.20')['ticket'],deviceId=str(uuid.uuid4()))
        self.assertEqual(self.post(p)[0],403)
    def test_ticket_expiry(self):
        p=self.payload();self.pair.tokens[p['ticket']]=('127.0.0.1',time.monotonic()-1)
        self.assertEqual(self.post(p)[0],403)
    def test_closed_window_blocks_new_key(self):
        p=self.payload();self.pair.deadline=time.monotonic()-1
        self.assertFalse(self.ad()['open']);self.assertEqual(self.post(p)[0],403)
    def test_origin_request_rejected(self):
        self.assertEqual(self.post(self.payload(),{'Content-Type':'application/json','Origin':'https://other.example'})[0],403)
    def test_invalid_uuid_rejected(self):
        p=self.payload();p['deviceId']='bad';self.assertEqual(self.post(p)[0],400)
    def test_wrong_content_type_rejected(self):
        self.assertEqual(self.post(self.payload(),{'Content-Type':'text/plain'})[0],400)
    def test_invalid_department_no_key_issued(self):
        self.server.config['pairingDepartment']='missing'
        self.assertEqual(self.post(self.payload())[0],409);self.assertEqual(self.pair.issued,0)
    def test_enrollment_capacity(self):
        p=self.payload();self.pair.issued=64;self.assertEqual(self.post(p)[0],403)
    def test_key_proof_allows_rediscovery_after_window_closed(self):
        secret=hashlib.sha256(b'test-existing-key').digest();self.pair.deadline=0
        ad=self.ad(fingerprint=secret.hex()[:32]);self.assertFalse(ad['open'])
        expected=hmac.new(secret,f"{'a'*32}|127.0.0.1|http|{self.server.server_port}".encode(),hashlib.sha256).hexdigest()
        self.assertEqual(ad['proof'],expected)
        self.assertNotIn(secret.hex(),json.dumps(ad))
    def test_revoked_key_cannot_prove_identity(self):
        with self.server.connect() as db:db.execute("UPDATE api_keys SET active=0 WHERE key_id='main'")
        self.assertNotIn('proof',self.ad(fingerprint=hashlib.sha256(b'test-existing-key').hexdigest()[:32]))
    def test_no_key_without_discovery_ticket(self):
        self.assertEqual(self.post(dict(ticket='invented',deviceId=str(uuid.uuid4())))[0],403)
    def test_discovery_rate_limit(self):
        for _ in range(30):self.assertIsNotNone(self.ad())
        self.assertIsNone(self.ad())
    def test_disabled_service_rejects_enrollment(self):
        self.server.lan_pairing=None
        try:self.assertEqual(self.post(self.payload())[0],403)
        finally:self.server.lan_pairing=self.pair
    def test_launch_defaults_and_explicit_disable(self):
        sys.path.insert(0,str(ROOT.parent/'tools'))
        from connection import load_connection
        cfg=json.loads((ROOT.parent/'connection.json').read_text())
        cfg['pcLanIp']='192.168.50.10';cfg.pop('lanPairingSeconds',None)
        path=Path(self.tmp.name)/'connection.json'
        cfg['developmentHttp']=True;path.write_text(json.dumps(cfg))
        self.assertEqual(load_connection(path)['lanPairingSeconds'],0)
        cfg['developmentHttp']=False;path.write_text(json.dumps(cfg))
        self.assertEqual(load_connection(path)['lanPairingSeconds'],0)
        cfg['developmentHttp']=True;cfg['lanPairingSeconds']=0;path.write_text(json.dumps(cfg))
        self.assertEqual(load_connection(path)['lanPairingSeconds'],0)
        cfg['lanPairingSeconds']=3600;path.write_text(json.dumps(cfg))
        with self.assertRaises(ValueError):load_connection(path)

if __name__=='__main__':unittest.main()
