#!/usr/bin/env python3
"""Renders frame_lenovo_health.yaml's templates against companion snapshots shaped like the recorded 10/14-10/24 ones."""
import unittest
from pathlib import Path
from types import SimpleNamespace

import yaml
from jinja2 import Environment

PACKAGE = yaml.safe_load((Path(__file__).parent / 'frame_lenovo_health.yaml').read_text())
SERVER = 1_792_872_000_000  # 2026-10-24 20:00 UTC
MINUTE = 60_000


def device(label, *, seen=0, photo=0, mode='photos', paused=False, offline=False, device_id=None):
    """A companion device; seen and photo are minutes before the server time."""
    return {'id': device_id or label.lower().replace(' ', '-'), 'label': label, 'online': seen < 1.5,
            'lastSeenAt': SERVER - seen * MINUTE,
            'status': {'mode': mode, 'photosPaused': paused, 'offline': offline, 'lastPhotoAt': SERVER - photo * MINUTE}}


def companion(*devices, state='online'):
    return SimpleNamespace(state=state, attributes={'devices': list(devices), 'serverTime': SERVER})


def render(template, sensor):
    env = Environment(extensions=['jinja2.ext.loopcontrols'])
    states = {'sensor.frame_companion': sensor}
    env.globals.update(
        state_attr=lambda entity, key: (states[entity].attributes.get(key) if states.get(entity) else None),
        is_state=lambda entity, value: bool(states.get(entity)) and states[entity].state == value,
    )
    return env.from_string(template).render().strip()


class LenovoHealthPackageTest(unittest.TestCase):
    sensors = {s['unique_id']: s for s in PACKAGE['template'][0]['binary_sensor']}
    automations = {a['id']: a for a in PACKAGE['automation']}

    def reporting(self, sensor):
        return render(self.sensors['frameos_frames_reporting']['state'], sensor)

    def stuck(self, sensor):
        return render(self.sensors['frameos_photos_stuck']['state'], sensor)

    def test_both_lenovos_checking_in_every_few_seconds_are_reporting(self):
        sensor = companion(device('Living room', seen=0.05), device('Parents', seen=0.1))
        self.assertEqual('True', self.reporting(sensor))
        self.assertEqual('', render(self.sensors['frameos_frames_reporting']['attributes']['stale'], sensor))

    def test_a_frame_silent_for_ten_minutes_is_named(self):
        sensor = companion(device('Living room', seen=0.05), device('Parents', seen=11))
        self.assertEqual('False', self.reporting(sensor))
        self.assertEqual('Parents', render(self.sensors['frameos_frames_reporting']['attributes']['stale'], sensor))

    def test_a_missing_companion_reading_does_not_claim_every_frame_is_down(self):
        self.assertEqual('True', self.reporting(companion(state='unavailable')))
        self.assertEqual('True', self.reporting(None))
        self.assertEqual('False', self.stuck(None))

    def test_the_10_14_frozen_photo_is_stuck(self):
        # Living room on 0.2.11: online, on Photos, not paused, the same photo for hours.
        sensor = companion(device('Living room', seen=0.05, photo=180), device('Parents', seen=0.05, photo=0.4))
        self.assertEqual('True', self.stuck(sensor))
        self.assertEqual('Living room', render(self.sensors['frameos_photos_stuck']['attributes']['frames'], sensor))

    def test_returning_to_photos_after_a_long_weather_visit_is_not_stuck(self):
        # Since 0.2.12 the only old-photo readings are the first seconds back on Photos after another view.
        self.assertEqual('False', self.stuck(companion(device('Living room', seen=0.05, photo=14))))

    def test_paused_held_other_views_offline_photos_and_silent_frames_are_not_stuck(self):
        for case in (dict(paused=True), dict(mode='weather'), dict(offline=True), dict(seen=11)):
            with self.subTest(**case):
                self.assertEqual('False', self.stuck(companion(device('Living room', photo=180, **{'seen': 0.05, **case}))))

    def test_alerts_debounce_and_go_through_the_household_notifier(self):
        reporting = self.automations['frameos_frame_stopped_reporting']['triggers'][0]
        stuck = self.automations['frameos_photos_stuck']['triggers'][0]
        remote = self.automations['frame_remote_unreachable']['triggers'][0]
        self.assertEqual(('binary_sensor.frameos_frames_reporting', 'off', {'minutes': 10}), (reporting['entity_id'], reporting['to'], reporting['for']))
        self.assertEqual(('binary_sensor.frameos_photos_stuck', 'on', {'minutes': 10}), (stuck['entity_id'], stuck['to'], stuck['for']))
        self.assertEqual(('sensor.frame_companion', 'unavailable', {'minutes': 30}), (remote['entity_id'], remote['to'], remote['for']))
        for automation in self.automations.values():
            action = automation['actions'][0]
            self.assertEqual('script.notify_household', action['action'])
            self.assertEqual('warning', action['data']['severity'])
            self.assertTrue(action['data']['notification_id'].startswith('frameos_'))


if __name__ == '__main__':
    unittest.main()
