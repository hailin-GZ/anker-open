"""Meaningful public-boundary regressions: tenant isolation, roles and enrollment replay."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
import uuid

spec=importlib.util.spec_from_file_location('cloud_gateway',Path(__file__).with_name('gateway.py'))
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)

class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        self.config={'token_secret':'s'*64,'activation_sha256':hashlib.sha256(b'activation').hexdigest(),
            'public_origin':'https://example.invalid','sdk_auth':{'test_only':True},'max_installations':3}
        self.registry=g.Registry(self.root/'registry.db',self.config)
    def tearDown(self):self.registry.db.close();self.tmp.cleanup()
    def enroll(self,identity=None,proof='p'*48):
        identity=identity or str(uuid.uuid4());return identity,self.registry.enroll({'installation_id':identity,'installation_secret':proof,'activation_code':'activation'})
    def test_roles_and_installations_are_distinct(self):
        a,ca=self.enroll();b,cb=self.enroll()
        values={self.registry.token(x,r) for x in (a,b) for r in ('read','write','analysis_read','control','reply')}
        self.assertEqual(len(values),10)
        for role in ('read','write','analysis_read','control','reply'):
            self.assertEqual(self.registry.authorize('Bearer '+self.registry.token(a,role),role),a)
            self.assertIsNone(self.registry.authorize('Bearer '+self.registry.token(a,'write'),'read'))
        self.assertNotIn('s'*64,json.dumps(ca))
    def test_reinstall_identity_cannot_be_claimed_without_its_proof(self):
        identity,c=self.enroll()
        self.assertEqual(c,self.enroll(identity)[1])
        with self.assertRaises(PermissionError):self.enroll(identity,'other-proof'*5)
    def test_bad_code_and_extra_input_rejected(self):
        data={'installation_id':str(uuid.uuid4()),'installation_secret':'p'*48,'activation_code':'wrong'}
        with self.assertRaises(PermissionError):self.registry.enroll(data)
        data['activation_code']='activation';data['model']='attacker-chosen'
        with self.assertRaises(ValueError):self.registry.enroll(data)
    def test_registry_restart_retains_identity_and_roles(self):
        identity,c=self.enroll();self.registry.db.close();self.registry=g.Registry(self.root/'registry.db',self.config)
        self.assertEqual(self.registry.authorize('Bearer '+c['backend']['write_token'],'write'),identity)
    def test_code_free_enrollment_is_opt_in_and_preserves_identity_proof(self):
        data={'installation_id':str(uuid.uuid4()),'installation_secret':'p'*48}
        with self.assertRaises(PermissionError):self.registry.enroll(data)
        self.config['allow_code_free_enrollment']=True
        first=self.registry.enroll(data)
        self.assertEqual(first,self.registry.enroll(data))
        self.assertEqual(self.registry.authorize('Bearer '+first['backend']['write_token'],'write'),data['installation_id'])
        with self.assertRaises(PermissionError):self.registry.enroll(dict(data,installation_secret='x'*48))
        with self.assertRaises(PermissionError):self.registry.enroll(dict(data,activation_code='wrong'))
        self.assertEqual(first,self.registry.enroll(dict(data,activation_code='activation')))
    def test_code_free_http_stable_client_compatibility_and_isolation(self):
        self.config['allow_code_free_enrollment']=True
        server=g.Server(('127.0.0.1',0),self.registry,None);threading.Thread(target=server.serve_forever,daemon=True).start()
        base='http://127.0.0.1:'+str(server.server_port)
        def post(data):
            with urllib.request.urlopen(urllib.request.Request(base+'/v1/enroll',data=json.dumps(data).encode())) as response:return json.load(response)
        try:
            stable={'installation_id':str(uuid.uuid4()),'installation_secret':'s'*48,'activation_code':'activation'}
            latest={'installation_id':str(uuid.uuid4()),'installation_secret':'n'*48}
            a=post(stable);b=post(latest)
            self.assertEqual(a,post(stable));self.assertEqual(b,post(latest))
            self.assertNotEqual(a['reply']['read_token'],b['reply']['read_token'])
            self.assertEqual(a['sdk_auth'],b['sdk_auth'])
            for invalid in (dict(latest,installation_secret='x'*48),dict(latest,activation_code='wrong')):
                with self.assertRaises(urllib.error.HTTPError) as caught:post(invalid)
                self.assertEqual(caught.exception.code,403)
        finally:server.shutdown();server.server_close()
    def test_tenant_storage_and_capacity(self):
        instances=[]
        def factory(identity,config,data):
            data.mkdir(parents=True);obj=object();instances.append((identity,data));return obj
        manager=g.Manager(dict(self.config,max_active_installations=2),self.root/'tenants',factory)
        a,b,c=[str(uuid.uuid4()) for _ in range(3)]
        self.assertIs(manager.get(a),manager.get(a));self.assertIsNot(manager.get(a),manager.get(b))
        self.assertNotEqual(instances[0][1],instances[1][1])
        with self.assertRaises(OverflowError):manager.get(c)
    def test_reply_format_strict_and_no_neutral_fallback(self):
        value={'options':['好的（温和）','不了（婉拒）','再看看（保留）','怎么走（疑问）']}
        self.assertEqual(g.validate_options(value),value)
        for bad in ({'options':['ok']*4},{'options':['好的（温和）']*4},{'options':value['options'],'extra':'no'}):
            with self.assertRaises(ValueError):g.validate_options(bad)
    def test_http_unauthorized_never_starts_models_or_exposes_sdk(self):
        started=[]
        class Manager:
            def get(self,*a):started.append(a);raise AssertionError('Unauthenticated startup')
        server=g.Server(('127.0.0.1',0),self.registry,Manager());threading.Thread(target=server.serve_forever,daemon=True).start()
        base='http://127.0.0.1:'+str(server.server_port)
        try:
            for path,method in [('/v1/transcripts','GET'),('/v1/analysis','GET'),('/v1/audio/chunks','POST'),('/v1/replies','POST'),('/v1/reply-interest','POST')]:
                request=urllib.request.Request(base+path,data=b'{}' if method=='POST' else None,method=method)
                with self.assertRaises(urllib.error.HTTPError) as caught:urllib.request.urlopen(request)
                self.assertEqual(caught.exception.code,401)
            self.assertFalse(started)
            with urllib.request.urlopen(base+'/healthz') as response:
                body=json.load(response);self.assertEqual(body['status'],'ok');self.assertNotIn('sdk_auth',body)
        finally:server.shutdown();server.server_close()
    def test_enrollment_http_capacity_and_idempotence(self):
        server=g.Server(('127.0.0.1',0),self.registry,None);threading.Thread(target=server.serve_forever,daemon=True).start()
        base='http://127.0.0.1:'+str(server.server_port)
        try:
            data={'installation_id':str(uuid.uuid4()),'installation_secret':'p'*48,'activation_code':'activation'}
            def post():
                with urllib.request.urlopen(urllib.request.Request(base+'/v1/enroll',data=json.dumps(data).encode())) as response:return json.load(response)
            self.assertEqual(post(),post())
            data['installation_secret']='x'*48
            with self.assertRaises(urllib.error.HTTPError) as caught:post()
            self.assertEqual(caught.exception.code,403)
        finally:server.shutdown();server.server_close()

if __name__=='__main__':unittest.main()
