#!/usr/bin/env python3
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import yaml
from jinja2 import Environment


class Loader(yaml.SafeLoader):
    pass


Loader.add_constructor('!secret', lambda loader, node: loader.construct_scalar(node))
ROOT = Path(__file__).parent
HEALTH = ('Frame Last Visible Render', 'Frame Recovery Count', 'Frame Last Failure')


class HealthSensorTemplateTest(unittest.TestCase):
    def setUp(self):
        package = yaml.load((ROOT / 'frame_companion.yaml').read_text(), Loader=Loader)
        self.sensors = {sensor['name']: sensor for sensor in package['template'][0]['sensor']}

    def render(self, name, field, device, *, state='online', server_time=990_000,
               now_ms=1_000_000, updated_ms=990_000):
        env = Environment()
        env.filters['timestamp_local'] = lambda value: f'ISO:{int(value)}'
        env.globals.update(
            state_attr=lambda entity, attribute: {
                'devices': [device], 'serverTime': server_time,
            }.get(attribute),
            is_state=lambda entity, value: entity == 'sensor.frame_companion' and value == state,
            now=lambda: datetime.fromtimestamp(now_ms / 1000, tz=timezone.utc),
            as_timestamp=lambda value, default=0: value.timestamp() if hasattr(value, 'timestamp') else default,
            states=SimpleNamespace(sensor=SimpleNamespace(frame_companion=SimpleNamespace(
                last_updated=datetime.fromtimestamp(updated_ms / 1000, tz=timezone.utc),
            ))),
        )
        return env.from_string(self.sensors[name][field]).render().strip().lower()

    @staticmethod
    def device(status, *, online=True, last_seen_at=980_000):
        return {'id': 'main', 'online': online, 'lastSeenAt': last_seen_at, 'status': status}

    def test_fresh_companion_payload_including_reserve_active_is_available(self):
        device = self.device({
            'lastPaintAt': 12_000, 'recoveryCount': 15,
            'lastError': 'Photos data stale', 'offline': True,
        })
        self.assertEqual(self.render('Frame Last Visible Render', 'state', device), 'iso:12')
        self.assertEqual(self.render('Frame Recovery Count', 'state', device), '15')
        self.assertEqual(self.render('Frame Last Failure', 'state', device), 'photos data stale')
        for name in HEALTH:
            self.assertEqual(self.render(name, 'availability', device), 'true', name)

    def test_missing_future_stale_and_device_offline_payloads_fail_closed(self):
        valid = self.device({'lastPaintAt': 12_000, 'recoveryCount': 15, 'lastError': 'last failure'})
        required = {
            'Frame Last Visible Render': 'lastPaintAt',
            'Frame Recovery Count': 'recoveryCount',
            'Frame Last Failure': 'lastError',
        }
        for name, field in required.items():
            missing = self.device({key: value for key, value in valid['status'].items() if key != field})
            self.assertEqual(self.render(name, 'availability', missing), 'false', f'{name} missing')
            self.assertEqual(self.render(name, 'availability', valid, server_time=800_000), 'false', f'{name} stale')
            self.assertEqual(self.render(name, 'availability', self.device(valid['status'], online=False)), 'false', f'{name} device offline')

        future = self.device({**valid['status'], 'lastPaintAt': 1_000_001})
        self.assertEqual(self.render('Frame Last Visible Render', 'state', future), 'unknown')
        self.assertEqual(self.render('Frame Last Visible Render', 'availability', future), 'false')

    def test_last_failure_distinguishes_missing_invalid_and_empty_values(self):
        base = {'lastPaintAt': 12_000, 'recoveryCount': 15}
        missing = self.device(base)
        invalid = self.device({**base, 'lastError': 9})
        empty = self.device({**base, 'lastError': ''})

        self.assertEqual(self.render('Frame Last Failure', 'state', missing), 'unknown')
        self.assertEqual(self.render('Frame Last Failure', 'availability', missing), 'false')
        self.assertEqual(self.render('Frame Last Failure', 'state', invalid), 'unknown')
        self.assertEqual(self.render('Frame Last Failure', 'availability', invalid), 'false')
        self.assertEqual(self.render('Frame Last Failure', 'state', empty), 'none')
        self.assertEqual(self.render('Frame Last Failure', 'availability', empty), 'true')


if __name__ == '__main__':
    unittest.main()
