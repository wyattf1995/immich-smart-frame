import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / 'server.py'
spec = importlib.util.spec_from_file_location('frame_companion', MODULE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class CompanionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.clock = 1_800_000_000_000
        self.store = module.FrameStore(Path(self.tmp.name) / 'state.db', {'main': {'label':'Living room','settings': {}}}, ['balanced','family'], clock=lambda:self.clock)
    def tearDown(self):
        self.tmp.cleanup()
    def poll(self, device='main', acks=None):
        return self.store.poll(device, {'schema':1,'status':{'mode':'photos','photosPaused':False,'lastPaintAt':self.clock,'appVersion':'0.2.0'},'acks':acks or []})
    def test_events_require_opt_in_and_expire(self):
        event={'type':'calendar','text':'An event starts soon','expiresInSeconds':120}
        with self.assertRaises(module.FrameError):self.store.event('main',event)
        self.store.settings('main',{'eventOverlays':True})
        saved=self.store.event('main',event)
        self.assertEqual(self.poll()['events'][0]['id'],saved['id'])
        with self.assertRaises(module.FrameError) as e:self.store.event('main',event)
        self.assertEqual(e.exception.status,429)
        self.clock+=121000;self.assertEqual(self.poll()['events'],[])
    def test_events_never_accept_unreviewed_birds_or_html(self):
        self.store.settings('main',{'eventOverlays':True})
        for event in [{'type':'model_candidate','text':'bird','expiresInSeconds':120},{'type':'reviewed_bird','text':'<script>bad</script>','expiresInSeconds':120},{'type':'calendar','text':'x'*101,'expiresInSeconds':120},{'type':'calendar','text':'event','expiresInSeconds':3600}]:
            with self.assertRaises(module.FrameError):self.store.event('main',event)
    def test_profile_command_persists_desired_setting(self):
        self.poll();self.store.command('main',{'type':'set_profile','profile':'family'})
        result=self.poll();self.assertEqual(result['settings']['profile'],'family');self.assertEqual(result['settingsRevision'],2)
    def test_offline_frames_cannot_accumulate_commands(self):
        with self.assertRaises(module.FrameError) as e: self.store.command('main', {'type':'photo_next'})
        self.assertEqual(e.exception.status,409)
    def test_commands_survive_restart_and_expire_without_replay(self):
        self.poll(); command=self.store.command('main', {'type':'photo_next'})
        reopened=module.FrameStore(Path(self.tmp.name)/'state.db',{},['balanced','family'],clock=lambda:self.clock)
        self.assertEqual(reopened.poll('main',{'status':{},'acks':[]})['commands'][0]['id'],command['id'])
        self.clock+=61000
        self.assertEqual(self.poll()['commands'],[])
        self.assertEqual(self.store.state()['devices'][0]['commands'][0]['status'],'expired')
    def test_ack_is_device_bound_and_terminal(self):
        self.poll(); c=self.store.command('main',{'type':'show_mode','mode':'weather'})
        self.poll(acks=[{'id':c['id'],'status':'applied','message':'complete'}]); self.poll(acks=[{'id':c['id'],'status':'failed','message':'later'}])
        self.assertEqual(self.store.state()['devices'][0]['commands'][0]['status'],'applied')
        self.assertEqual(self.poll()['commands'],[])
    def test_settings_atomic_validation_and_preserved_on_bad_input(self):
        before=self.store.state()['devices'][0]['settings']
        for patch in [{'modeOrder':[]},{'profile':'../../bad'},{'quietHours':{'enabled':True,'start':'25:00','end':'07:00','brightness':10}},{'companionUrl':'http://evil'}]:
            with self.assertRaises(module.FrameError): self.store.settings('main',patch)
            self.assertEqual(self.store.state()['devices'][0]['settings'],before)
        self.store.settings('main',{'modeOrder':['photos','weather'],'quietHours':{'enabled':True,'start':'22:00','end':'07:00','brightness':10}})
        result=self.poll();self.assertEqual(result['settings']['modeOrder'],['photos','weather']);self.assertEqual(result['settingsRevision'],2)
    def test_command_allowlist_and_queue_limit(self):
        self.poll()
        for payload in [{'type':'shell','command':'reboot'},{'type':'show_mode','mode':'settings'},{'type':'photo_hold','durationSeconds':99999},{'type':'set_profile','profile':'unknown'},{'type':'photo_next','url':'https://evil'}]:
            with self.assertRaises(module.FrameError): self.store.command('main',payload)
        for _ in range(16): self.store.command('main',{'type':'photo_next'})
        with self.assertRaises(module.FrameError) as e:self.store.command('main',{'type':'photo_next'})
        self.assertEqual(e.exception.status,429)
    def test_status_drops_unknown_secrets_and_bounds_strings(self):
        self.store.poll('main',{'status':{'mode':'photos','token':'secret','lastError':'x'*10000,'lastPaintAt':self.clock+999999999},'acks':[]})
        status=self.store.state()['devices'][0]['status'];self.assertNotIn('token',status);self.assertLessEqual(len(status.get('lastError','')),80);self.assertIsNone(status.get('lastPaintAt'))
    def test_auth_roles_cannot_cross_boundaries(self):
        config={'operatorUsername':'frame','operatorPassword':'p'*32,'operatorToken':'o'*48,'devices':{'main':{'token':'d'*48}}}
        auth=module.FrameAuth(config)
        self.assertEqual(auth.role('Bearer '+'d'*48),('device','main'))
        self.assertEqual(auth.role('Bearer '+'o'*48),('operator',None))
        self.assertIsNone(auth.role('Bearer '+'d'*47+'x'))
    def test_device_cannot_ack_another_frame(self):
        self.store=module.FrameStore(Path(self.tmp.name)/'state.db',{'parents':{}},['balanced','family'],clock=lambda:self.clock)
        self.poll();c=self.store.command('main',{'type':'photo_next'})
        self.store.poll('parents',{'status':{},'acks':[{'id':c['id'],'status':'applied'}]})
        self.assertEqual(self.poll()['commands'][0]['id'],c['id'])
    def test_preference_export_preserves_all_scopes(self):
        export=Path(self.tmp.name)/'export'/'preferences.json'
        self.store=module.FrameStore(Path(self.tmp.name)/'state.db',{'parents':{}},['balanced','family'],clock=lambda:self.clock,preferences_export=export)
        asset='11111111-1111-4111-8111-111111111111'
        self.store.feedback('main',{'assetId':asset,'preference':'hide'})
        data=json.loads(export.read_text());self.assertEqual(data['devices']['main']['assets'][asset],'hide');self.assertEqual(data['devices']['parents']['assets'],{})
        self.store.feedback('main',{'assetId':asset,'preference':'clear'})
        self.assertEqual(json.loads(export.read_text())['devices']['main']['assets'],{})
    def test_auth_invalid_unicode_and_role_token_reuse(self):
        config={'operatorUsername':'frame','operatorPassword':'p'*32,'operatorToken':'o'*48,'devices':{'main':{'token':'d'*48}}}
        auth=module.FrameAuth(config)
        self.assertIsNone(auth.role('Bearer ☃'))
        config['devices']['main']['token']=config['operatorToken']
        with self.assertRaises(module.FrameError):module.FrameAuth(config)
    def test_feedback_is_separate_reversible_and_scoped(self):
        self.store.feedback('main',{'assetId':'11111111-1111-4111-8111-111111111111','preference':'less'})
        self.assertEqual(self.store.preferences('main')[0]['preference'],'less')
        self.store.feedback('main',{'assetId':'11111111-1111-4111-8111-111111111111','preference':'clear'})
        self.assertEqual(self.store.preferences('main'),[])
        with self.assertRaises(module.FrameError):self.store.feedback('main',{'assetId':'../../etc','preference':'hide'})

class HttpBoundaryTests(unittest.TestCase):
    def setUp(self):
        import threading
        self.tmp=tempfile.TemporaryDirectory()
        config={'operatorUsername':'frame','operatorPassword':'p'*32,'operatorToken':'o'*48,'publicOrigin':'https://frame.example.com','devices':{'main':{'token':'d'*48,'label':'Living room'}}}
        self.store=module.FrameStore(Path(self.tmp.name)/'state.db',config['devices'],['balanced'])
        self.server=module.make_server(config,self.store,'127.0.0.1',0)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.tmp.cleanup()
    def request(self,method,path,token=None,body=None,headers=None):
        import http.client
        h=dict(headers or {})
        if token:h['Authorization']=token
        if body is not None:h['Content-Type']='application/json';body=json.dumps(body)
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3);c.request(method,path,body,h);r=c.getresponse();data=r.read();result=(r.status,dict(r.getheaders()),data);c.close();return result
    def test_roles_and_query_token_are_rejected(self):
        self.assertEqual(self.request('GET','/api/state?token='+'o'*48)[0],401)
        self.assertEqual(self.request('GET','/api/state','Bearer '+'d'*48)[0],403)
        self.assertEqual(self.request('POST','/device/poll','Bearer '+'o'*48,{})[0],403)
        self.assertEqual(self.request('POST','/device/poll','Bearer '+'d'*48,{'status':{},'acks':[]})[0],200)
    def test_basic_mutations_require_csrf_and_origin(self):
        import base64
        basic='Basic '+base64.b64encode(('frame:'+('p'*32)).encode()).decode()
        status,headers,body=self.request('GET','/api/state',basic);self.assertEqual(status,200)
        csrf=json.loads(body)['csrfToken']
        payload={'deviceId':'main','patch':{'idleReturnSeconds':600}}
        self.assertEqual(self.request('POST','/api/settings',basic,payload)[0],403)
        self.assertEqual(self.request('POST','/api/settings',basic,payload,{'Origin':'https://evil.example','X-Frame-CSRF':csrf})[0],403)
        self.assertEqual(self.request('POST','/api/settings',basic,payload,{'Origin':'https://frame.example.com','X-Frame-CSRF':csrf})[0],200)
    def test_operator_bearer_is_not_accepted_with_cross_origin_browser(self):
        self.assertEqual(self.request('POST','/api/settings','Bearer '+'o'*48,{'deviceId':'main','patch':{'idleReturnSeconds':600}}, {'Origin':'https://evil.example'})[0],403)

    def test_origin_normalizes_default_https_port_and_host_case_only(self):
        self.assertEqual(module.canonical_origin('https://FRAME.EXAMPLE.COM:443'), 'https://frame.example.com')
        self.assertEqual(module.canonical_origin('https://frame.example.com'), 'https://frame.example.com')
        self.assertEqual(module.canonical_origin('https://FRAME.EXAMPLE.COM:8443'), 'https://frame.example.com:8443')
        self.assertNotEqual(module.canonical_origin('https://frame.example.com:8443'), module.canonical_origin('https://frame.example.com'))

    def test_origin_rejects_userinfo_paths_and_evil_suffixes(self):
        trusted = module.canonical_origin('https://frame.example.com')
        self.assertIsNone(module.canonical_origin('https://user@frame.example.com'))
        self.assertIsNone(module.canonical_origin('https://frame.example.com/control'))
        self.assertNotEqual(module.canonical_origin('https://frame.example.com.evil'), trusted)
    def test_unknown_paths_and_methods_never_proxy(self):
        self.assertEqual(self.request('GET','/../../etc/passwd','Bearer '+'o'*48)[0],404)
        self.assertEqual(self.request('POST','/api/reboot','Bearer '+'o'*48,{})[0],404)
        self.assertEqual(self.request('DELETE','/api/state','Bearer '+'o'*48)[0],405)

    def test_authenticated_ui_and_preferences_are_no_store(self):
        status,headers,body=self.request('GET','/','Bearer '+'o'*48)
        self.assertEqual(status,200);self.assertEqual(headers['Cache-Control'],'no-store');self.assertIn(b'Frame remote',body)
        self.assertEqual(self.request('GET','/api/preferences?deviceId=main','Bearer '+'o'*48)[0],200)
        self.assertEqual(self.request('GET','/api/preferences?deviceId=main','Bearer '+'d'*48)[0],403)
    def test_non_object_and_oversize_posts_rejected(self):
        self.assertEqual(self.request('POST','/device/poll','Bearer '+'d'*48,[])[0],400)
        self.assertEqual(self.request('POST','/device/poll','Bearer '+'d'*48,{'x':'x'*40000})[0],413)

if __name__=='__main__': unittest.main()
