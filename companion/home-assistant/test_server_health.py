#!/usr/bin/env python3
"""Exercise the actual HA server-health templates with aged and invalid evidence."""
import unittest
from datetime import datetime, timezone
from pathlib import Path

import yaml
from jinja2 import Environment


class ServerHealthTemplatesTest(unittest.TestCase):
    def setUp(self):
        package = yaml.safe_load((Path(__file__).parent / 'frame_server_health.yaml').read_text())
        self.sensors = {item['name']: item for group in package['template']
                        for kind in ('sensor', 'binary_sensor') for item in group.get(kind, [])}
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


if __name__ == '__main__':
    unittest.main()
