#!/usr/bin/env python3
import importlib.util, json, tempfile, unittest
from pathlib import Path
import yaml
from jinja2 import Environment

class Loader(yaml.SafeLoader): pass
Loader.add_constructor('!secret', lambda loader, node: loader.construct_scalar(node))
ROOT=Path(__file__).parent
spec=importlib.util.spec_from_file_location('server', ROOT.parent / 'server.py')
server=importlib.util.module_from_spec(spec); spec.loader.exec_module(server)

class ProtocolTest(unittest.TestCase):
 def setUp(self):
  self.data=yaml.load((ROOT/'frame_companion.yaml').read_text(), Loader=Loader)
  self.store=server.FrameStore(Path(tempfile.mkdtemp())/'state.db', {'main':{}}, ['balanced','family','photography'], clock=lambda:100000)
  self.store.poll('main', {'status': {'mode':'photos'}})
  self.env=Environment(); self.env.filters['to_json']=json.dumps; self.env.filters['timestamp_local']=lambda value: f'ISO:{int(value)}'
 def command(self, payload):
  rendered=self.env.from_string(self.data['rest_command']['postframecommand']['payload']).render(payload=payload)
  body=json.loads(rendered); return self.store.command(body['deviceId'],body['command'])
 def test_rendered_command_payloads_match_framestore(self):
  for command in [{'type':'photo_next'},{'type':'photo_previous'},{'type':'photo_pause'},{'type':'photo_resume'},{'type':'photo_hold','durationSeconds':600},{'type':'show_mode','mode':'birds'},{'type':'set_profile','profile':'family'}]:
   self.assertEqual(self.command({'deviceId':'main','command':command})['type'], command['type'])
 def test_rendered_event_payload_matches_framestore(self):
  self.store.settings('main', {'eventOverlays':True})
  rendered=self.env.from_string(self.data['rest_command']['postframeevent']['payload']).render(payload={'deviceId':'main','event':{'type':'calendar','text':'Safe text','expiresInSeconds':120}})
  body=json.loads(rendered); self.assertEqual(self.store.event(body['deviceId'],body['event'])['type'], 'calendar')
 def test_state_template_targets_main_device(self):
  template=self.data['rest'][0]['sensor'][0]['value_template']
  self.assertEqual(self.env.from_string(template).render(value_json={'devices':[{'id':'other','online':True},{'id':'main','online':False}]}).strip(), 'offline')
 def test_state_templates_handle_startup_and_populated_values(self):
  sensors = {item['name']: item for item in self.data['template'][0]['sensor']}
  def render(name, devices):
   return self.env.from_string(sensors[name]['state']).render(state_attr=lambda *_: devices).strip()
  for devices in (None, []):
   for name in sensors:
    self.assertEqual(render(name, devices), 'unknown')
  missing = [{'id':'main', 'status':{}, 'commands':[]}]
  for name in sensors:
   self.assertEqual(render(name, missing), 'unknown')
  populated = [{'id':'main', 'status':{'lastPhotoAt':100000,'lastWeatherAt':101000,'offlineAssets':0,'appVersion':'0.3.0'}, 'commands':[{'status':'applied'}]}]
  self.assertEqual(render('Frame Last Photo', populated), 'ISO:100')
  self.assertEqual(render('Frame Last Weather', populated), 'ISO:101')
  self.assertEqual(render('Frame Reserve Count', populated), '0')
  self.assertEqual(render('Frame Version', populated), '0.3.0')
  self.assertEqual(render('Frame Command Acknowledgement', populated), 'applied')
if __name__=='__main__': unittest.main()
