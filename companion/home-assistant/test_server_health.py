#!/usr/bin/env python3
"""Exercise the actual HA server-health templates with aged and invalid evidence."""
import unittest
from ast import literal_eval
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import yaml
from jinja2 import Environment
from jinja2.nativetypes import NativeEnvironment


class Loader(yaml.SafeLoader):
    pass


Loader.add_constructor('!secret', lambda loader, node: loader.construct_scalar(node))

WEBHOOK_ATTRIBUTES = {
    'observedAt', 'audioLastAt', 'audioHealthy',
    'kioskRestarts', 'kioskHealthy', 'kioskOom',
    'birdnetRestarts', 'birdnetHealthy', 'birdnetOom',
    'bridgeRestarts', 'bridgeHealthy', 'bridgeOom',
}


class ServerHealthTemplatesTest(unittest.TestCase):
    def setUp(self):
        package = yaml.load((Path(__file__).parent / 'frame_server_health.yaml').read_text(), Loader=Loader)
        self.sensors = {item['name']: item for group in package['template']
                        for kind in ('sensor', 'binary_sensor') for item in group.get(kind, [])}
        self.webhook = next(group for group in package['template'] if 'triggers' in group)
        self.webhook_sensor = self.webhook['sensor'][0]
        self.attrs = {'observedAt': 990000, 'audioLastAt': 989000, 'audioHealthy': True,
                      'kioskRestarts': 0, 'birdnetRestarts': 2, 'bridgeRestarts': 1}

    def render(self, name, field='state', attrs=None, state='healthy'):
        attrs = self.attrs if attrs is None else attrs
        env = Environment()
        env.globals.update(state_attr=lambda *_: attrs.get(_[1]), states=lambda _: state,
                           now=lambda: datetime.fromtimestamp(1000, timezone.utc),
                           as_timestamp=lambda value: value.timestamp())
        env.filters['timestamp_local'] = lambda value: f'ISO:{value:g}'
        return env.from_string(self.sensors[name][field]).render().strip()

    def test_fresh_values_and_audio_age(self):
        self.assertEqual(self.render('Frame Services'), 'healthy')
        self.assertEqual(self.render('Frame Audio Age'), '11')
        self.assertEqual(self.render('Frame Audio Last Received'), 'ISO:989')
        self.assertEqual(self.render('Frame Kiosk Restarts'), '0')
        self.assertEqual(self.render('Frame BirdNET Restarts'), '2')
        self.assertEqual(self.render('Frame Audio Bridge Restarts'), '1')
        self.assertEqual(self.render('Frame Server Telemetry Fresh'), 'True')
        for name in self.sensors:
            if 'availability' in self.sensors[name]:
                self.assertEqual(self.render(name, 'availability'), 'True', name)

    def test_missing_stale_and_future_snapshot_fails_closed(self):
        for attrs in ({}, {**self.attrs, 'observedAt': 700000},
                      {**self.attrs, 'observedAt': 1000001}, {**self.attrs, 'observedAt': 'bad'}):
            self.assertEqual(self.render('Frame Server Telemetry Fresh', attrs=attrs), 'False')
            for name in self.sensors:
                if 'availability' in self.sensors[name]:
                    self.assertEqual(self.render(name, 'availability', attrs), 'False', name)

    def test_missing_and_future_audio_does_not_claim_freshness(self):
        for value in (None, 'bad', 1000001, -1):
            attrs = {**self.attrs, 'audioLastAt': value}
            for name in ('Frame Audio Age', 'Frame Audio Last Received'):
                self.assertEqual(self.render(name, 'availability', attrs), 'False')
                self.assertEqual(self.render(name, attrs=attrs), 'unknown')
        attrs = {**self.attrs, 'audioLastAt': 800000, 'audioHealthy': False}
        self.assertEqual(self.render('Frame Audio Age', attrs=attrs), '200')
        self.assertEqual(self.render('Frame Services', state='degraded'), 'degraded')

    def test_missing_counts_are_not_zero(self):
        for name, key in (('Frame Kiosk Restarts', 'kioskRestarts'),
                          ('Frame BirdNET Restarts', 'birdnetRestarts'),
                          ('Frame Audio Bridge Restarts', 'bridgeRestarts')):
            for value in (None, -1, 'bad', True):
                attrs = {**self.attrs, key: value}
                self.assertEqual(self.render(name, 'availability', attrs), 'False')
                self.assertEqual(self.render(name, attrs=attrs), 'unknown')

    def render_webhook(self, field, payload):
        env = NativeEnvironment()
        rendered = env.from_string(self.webhook_sensor[field]).render(
            trigger=SimpleNamespace(json=payload),
        )
        return rendered.strip() if isinstance(rendered, str) else rendered

    def render_webhook_attributes(self, payload):
        env = NativeEnvironment()
        def render(template):
            value = env.from_string(template).render(trigger=SimpleNamespace(json=payload))
            if not isinstance(value, str):
                return value
            try:
                return literal_eval(value.strip())
            except (SyntaxError, ValueError):
                return value.strip()
        return {
            name: render(template)
            for name, template in self.webhook_sensor['attributes'].items()
        }

    def test_local_post_webhook_is_the_only_writer(self):
        self.assertEqual(self.webhook_sensor['name'], 'Frame Server Health')
        self.assertEqual(self.webhook_sensor['unique_id'], 'frame_server_health_webhook')
        self.assertEqual(set(self.webhook), {'triggers', 'sensor'})
        self.assertEqual(len(self.webhook['triggers']), 1)
        trigger = self.webhook['triggers'][0]
        self.assertEqual(trigger['trigger'], 'webhook')
        self.assertEqual(trigger['webhook_id'], 'frame_health_webhook_id')
        self.assertEqual(trigger['allowed_methods'], ['POST'])
        self.assertIs(trigger['local_only'], True)
        self.assertEqual(set(self.webhook_sensor['attributes']), WEBHOOK_ATTRIBUTES)

    def test_valid_webhook_payload_whitelists_only_typed_scalars(self):
        payload = {
            'state': 'degraded',
            'attributes': {
                'observedAt': 990000, 'audioLastAt': 989000, 'audioHealthy': True,
                'kioskRestarts': 0, 'kioskHealthy': True, 'kioskOom': False,
                'birdnetRestarts': 2, 'birdnetHealthy': False, 'birdnetOom': False,
                'bridgeRestarts': 1, 'bridgeHealthy': True, 'bridgeOom': False,
                'rawPayload': {'secret': 'must not be retained'},
            },
        }
        self.assertEqual(self.render_webhook('state', payload), 'degraded')
        attributes = self.render_webhook_attributes(payload)
        self.assertEqual(attributes['observedAt'], 990000)
        self.assertEqual(attributes['audioLastAt'], 989000)
        self.assertIs(attributes['audioHealthy'], True)
        self.assertEqual(attributes['birdnetRestarts'], 2)
        self.assertIs(attributes['birdnetHealthy'], False)
        self.assertNotIn('rawPayload', self.webhook_sensor['attributes'])

    def test_missing_or_malformed_webhook_payload_fails_closed(self):
        for payload in (None, [], {}, {'state': 'unexpected'}, {'state': 'healthy', 'attributes': []}):
            self.assertEqual(self.render_webhook('state', payload), 'unknown')
            attributes = self.render_webhook_attributes(payload)
            self.assertEqual(set(attributes), WEBHOOK_ATTRIBUTES)
            self.assertTrue(all(value is None for value in attributes.values()))

        invalid = {
            'state': 'healthy',
            'attributes': {
                'observedAt': True, 'audioLastAt': -1, 'audioHealthy': 'yes',
                'kioskRestarts': -1, 'kioskHealthy': 1, 'kioskOom': 'false',
            },
        }
        attributes = self.render_webhook_attributes(invalid)
        self.assertEqual(self.render_webhook('state', invalid), 'healthy')
        self.assertTrue(all(value is None for value in attributes.values()))


if __name__ == '__main__':
    unittest.main()
