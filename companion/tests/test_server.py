import importlib.util
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

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

    def test_max_event_ttl_is_bounded_when_clock_advances_between_reads(self):
        self.store.settings('main',{'eventOverlays':True})

        class AdvancingClock:
            def __init__(self, value):
                self.value = value

            def __call__(self):
                current = self.value
                self.value += 1
                return current

        self.store.clock = AdvancingClock(self.clock)
        event = self.store.event('main', {'type':'calendar','text':'Maximum duration','expiresInSeconds':300})
        self.assertEqual(event['expiresAt'] - event['issuedAt'], 300_000)
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

    def test_command_ttl_is_bounded_when_clock_advances_between_reads(self):
        self.poll()

        class AdvancingClock:
            def __init__(self, value):
                self.value = value

            def __call__(self):
                current = self.value
                self.value += 1
                return current

        self.store.clock = AdvancingClock(self.clock)
        command = self.store.command('main', {'type':'photo_next'})
        self.assertEqual(command['expiresAt'] - command['issuedAt'], 60_000)
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

    def test_quiet_brightness_preserves_the_device_minimum_wake_level(self):
        for brightness in (1,5,9,31):
            with self.assertRaises(module.FrameError):
                self.store.settings('main',{'quietHours':{'enabled':True,'start':'22:00','end':'07:00','brightness':brightness}})
        self.store.settings('main',{'quietHours':{'enabled':True,'start':'22:00','end':'07:00','brightness':10}})
        self.assertEqual(self.poll()['settings']['quietHours']['brightness'],10)
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

    def test_failed_export_keeps_sqlite_policy_dirty_then_retries(self):
        export = Path(self.tmp.name) / 'export' / 'preferences.json'
        self.store = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        prior = export.read_bytes()
        asset = '11111111-1111-4111-8111-111111111111'
        try:
            with patch.object(module.os, 'replace', side_effect=OSError('fixture replacement failure')):
                with self.assertRaises(OSError):
                    self.store.feedback('main', {'assetId':asset, 'preference':'hide'})
                with self.store.db(write=False) as db:
                    desired, published = self.store._export_revisions(db)
                self.assertGreater(desired, published)
                self.assertEqual(export.read_bytes(), prior)
                self.assertEqual(self.store.poll('main', {'status': {}})['hiddenAssets'], [asset])
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline and json.loads(export.read_text())['devices']['main']['assets'].get(asset) != 'hide':
                time.sleep(.01)
            self.assertEqual(json.loads(export.read_text())['devices']['main']['assets'][asset], 'hide')
            with self.store.db(write=False) as db:
                desired, published = self.store._export_revisions(db)
            self.assertEqual(published, desired)
        finally:
            self.store.close()

    def test_newer_feedback_is_published_after_older_snapshot(self):
        export = Path(self.tmp.name) / 'export' / 'preferences.json'
        self.store = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        first = '11111111-1111-4111-8111-111111111111'
        second = '22222222-2222-4222-8222-222222222222'
        at_replace = threading.Event()
        release_replace = threading.Event()
        original_replace = module.os.replace
        calls = []
        def blocked_replace(source, destination):
            calls.append((source, destination))
            if len(calls) == 1:
                at_replace.set()
                self.assertTrue(release_replace.wait(2))
            return original_replace(source, destination)
        first_error = []
        second_error = []
        worker = None
        second_worker = None
        def save_first():
            try:
                self.store.feedback('main', {'assetId':first, 'preference':'hide'})
            except Exception as error:
                first_error.append(error)
        def save_second():
            try:
                self.store.feedback('main', {'assetId':second, 'preference':'hide'})
            except Exception as error:
                second_error.append(error)
        try:
            with patch.object(module.os, 'replace', side_effect=blocked_replace):
                worker = threading.Thread(target=save_first)
                worker.start()
                self.assertTrue(at_replace.wait(2))
                second_worker = threading.Thread(target=save_second)
                second_worker.start()
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    with self.store.db(write=False) as db:
                        desired, _ = self.store._export_revisions(db)
                    if desired >= 4: break
                    time.sleep(.005)
                self.assertEqual(desired, 4)
                release_replace.set()
                worker.join(2); second_worker.join(2)
            self.assertFalse(worker.is_alive()); self.assertFalse(second_worker.is_alive()); self.assertEqual(first_error, []); self.assertEqual(second_error, [])
            self.assertEqual(len(calls), 2)
            data = json.loads(export.read_text())['devices']['main']['assets']
            self.assertEqual(data, {first:'hide', second:'hide'})
            with self.store.db(write=False) as db:
                desired, published = self.store._export_revisions(db)
            self.assertEqual((desired, published), (4, 4))
        finally:
            release_replace.set()
            if worker: worker.join(2)
            if second_worker: second_worker.join(2)
            self.store.close()

    def test_restart_recovers_dirty_export_and_close_cancels_retry(self):
        export = Path(self.tmp.name) / 'export' / 'preferences.json'
        self.store = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        asset = '11111111-1111-4111-8111-111111111111'
        with patch.object(module, 'EXPORT_RETRY_INITIAL_SECONDS', 60), patch.object(module.os, 'replace', side_effect=OSError('fixture replacement failure')):
            with self.assertRaises(OSError):
                self.store.feedback('main', {'assetId':asset, 'preference':'hide'})
            retry = self.store.export_retry_thread
            self.assertIsNotNone(retry)
            self.store.close()
            self.assertFalse(retry.is_alive())
        reopened = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        try:
            self.assertEqual(json.loads(export.read_text())['devices']['main']['assets'][asset], 'hide')
            with reopened.db(write=False) as db:
                desired, published = reopened._export_revisions(db)
            self.assertEqual(published, desired)
        finally:
            reopened.close()

    def test_startup_recreates_missing_export_and_includes_new_device(self):
        export = Path(self.tmp.name) / 'export' / 'preferences.json'
        first = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        first.close()
        export.unlink()
        reopened = module.FrameStore(Path(self.tmp.name) / 'state.db', {'other': {}}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        try:
            data = json.loads(export.read_text())['devices']
            self.assertEqual(set(data), {'main', 'other'})
            with reopened.db(write=False) as db:
                desired, published = reopened._export_revisions(db)
            self.assertEqual(published, desired)
        finally:
            reopened.close()

    def test_failed_startup_export_recovers_missing_file_and_new_device(self):
        export = Path(self.tmp.name) / 'export' / 'preferences.json'
        first = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        first.close()
        baseline = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        try:
            with baseline.db(write=False) as db:
                desired, published = baseline._export_revisions(db)
            self.assertEqual(desired, published)
        finally:
            baseline.close()
        export.unlink()
        reopened = None
        try:
            with patch.object(module, 'EXPORT_RETRY_INITIAL_SECONDS', .01), patch.object(module.os, 'replace', side_effect=OSError('fixture startup export failure')):
                reopened = module.FrameStore(Path(self.tmp.name) / 'state.db', {'other': {}}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
                with reopened.db(write=False) as db:
                    desired, published = reopened._export_revisions(db)
                self.assertGreater(desired, published)
                self.assertFalse(export.exists())
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline and not export.exists():
                time.sleep(.01)
            data = json.loads(export.read_text())['devices']
            self.assertEqual(set(data), {'main', 'other'})
            with reopened.db(write=False) as db:
                desired, published = reopened._export_revisions(db)
            self.assertEqual(desired, published)
        finally:
            if reopened: reopened.close()

    def test_retry_error_after_zero_delay_returns_to_positive_backoff(self):
        waits = []
        class RetryCancel:
            def wait(self, delay):
                waits.append(delay)
                return len(waits) >= 3
        original_cancel = self.store.export_retry_cancel
        self.store.export_retry_cancel = RetryCancel()
        try:
            with patch.object(self.store, 'export_preferences', side_effect=[True, OSError('fixture retry failure')]), patch.object(self.store, '_export_pending', return_value=True):
                self.store._retry_exports()
            self.assertEqual(waits, [module.EXPORT_RETRY_INITIAL_SECONDS, 0, module.EXPORT_RETRY_INITIAL_SECONDS])
        finally:
            self.store.export_retry_cancel = original_cancel

    def test_retry_handoff_starts_new_worker_after_concurrent_failure(self):
        export = Path(self.tmp.name) / 'export' / 'preferences.json'
        self.store = module.FrameStore(Path(self.tmp.name) / 'state.db', {}, ['balanced','family'], clock=lambda: self.clock, preferences_export=export)
        first = '11111111-1111-4111-8111-111111111111'
        second = '22222222-2222-4222-8222-222222222222'
        pending_checked = threading.Event()
        release_pending = threading.Event()
        original_pending = self.store._export_pending
        pending_calls = 0
        def paused_pending():
            nonlocal pending_calls
            pending_calls += 1
            result = original_pending()
            if pending_calls == 1:
                pending_checked.set()
                self.assertTrue(release_pending.wait(2))
            return result
        original_replace = module.os.replace
        replace_calls = 0
        def flaky_replace(source, destination):
            nonlocal replace_calls
            replace_calls += 1
            if replace_calls in (1, 3): raise OSError('fixture export failure')
            return original_replace(source, destination)
        second_error = []
        def save_second():
            try:
                self.store.feedback('main', {'assetId':second, 'preference':'hide'})
            except Exception as error:
                second_error.append(error)
        worker = None
        try:
            with patch.object(module, 'EXPORT_RETRY_INITIAL_SECONDS', .01), patch.object(module.os, 'replace', side_effect=flaky_replace), patch.object(self.store, '_export_pending', side_effect=paused_pending):
                with self.assertRaises(OSError):
                    self.store.feedback('main', {'assetId':first, 'preference':'hide'})
                self.assertTrue(pending_checked.wait(2))
                worker = threading.Thread(target=save_second)
                worker.start()
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    with self.store.db(write=False) as db:
                        desired, _ = self.store._export_revisions(db)
                    if desired == 4: break
                    time.sleep(.005)
                self.assertEqual(desired, 4)
                release_pending.set()
                worker.join(2)
                self.assertEqual(len(second_error), 1)
                self.assertIsInstance(second_error[0], OSError)
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                with self.store.db(write=False) as db:
                    desired, published = self.store._export_revisions(db)
                if desired == published: break
                time.sleep(.01)
            self.assertEqual(desired, published)
            data = json.loads(export.read_text())['devices']['main']['assets']
            self.assertEqual(data, {first:'hide', second:'hide'})
        finally:
            release_pending.set()
            if worker: worker.join(2)
            self.store.close()
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

    def test_device_receives_its_full_hidden_snapshot_for_offline_reserve(self):
        asset='11111111-1111-4111-8111-111111111111'
        self.store=module.FrameStore(Path(self.tmp.name)/'state.db',{'parents':{}},['balanced','family'],clock=lambda:self.clock)
        self.assertEqual(self.poll()['hiddenAssets'],[])
        self.store.feedback('main',{'assetId':asset,'preference':'hide'})
        self.assertEqual(self.poll()['hiddenAssets'],[asset])
        self.assertEqual(self.poll('parents')['hiddenAssets'],[])
        reopened=module.FrameStore(Path(self.tmp.name)/'state.db',{},['balanced','family'],clock=lambda:self.clock)
        self.assertEqual(reopened.poll('main',{'status':{}})['hiddenAssets'],[asset])
        self.store.feedback('main',{'assetId':asset,'preference':'clear'})
        self.assertEqual(self.poll()['hiddenAssets'],[])

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

    def test_operator_bearer_rejects_every_present_malformed_origin(self):
        payload={'deviceId':'main','patch':{'idleReturnSeconds':600}}
        for origin in ('http://evil.example','null','https://frame.example.com/control','https://user@frame.example.com','https://@frame.example.com','https://frame.example.com:bad'):
            with self.subTest(origin=origin):
                self.assertEqual(self.request('POST','/api/settings','Bearer '+'o'*48,payload, {'Origin':origin})[0],403)

    def test_origin_normalizes_default_https_port_and_host_case_only(self):
        self.assertEqual(module.canonical_origin('https://FRAME.EXAMPLE.COM:443'), 'https://frame.example.com')
        self.assertEqual(module.canonical_origin('https://frame.example.com'), 'https://frame.example.com')
        self.assertEqual(module.canonical_origin('https://FRAME.EXAMPLE.COM:8443'), 'https://frame.example.com:8443')
        self.assertNotEqual(module.canonical_origin('https://frame.example.com:8443'), module.canonical_origin('https://frame.example.com'))

    def test_origin_rejects_userinfo_paths_and_evil_suffixes(self):
        trusted = module.canonical_origin('https://frame.example.com')
        self.assertIsNone(module.canonical_origin('https://user@frame.example.com'))
        self.assertIsNone(module.canonical_origin('https://@frame.example.com'))
        self.assertIsNone(module.canonical_origin('https://frame.example.com/control'))
        self.assertIsNone(module.canonical_origin('https://frame.example.com\t'))
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
