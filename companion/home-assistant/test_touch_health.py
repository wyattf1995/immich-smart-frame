#!/usr/bin/env python3
"""Renders frame_touch_health.yaml's templates with fake frame states (fresh, stale, never reported, restart, update)."""
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import yaml
from jinja2 import Environment

PACKAGE = yaml.safe_load((Path(__file__).parent / 'frame_touch_health.yaml').read_text())
NOW = datetime(2026, 10, 17, 22, 0, tzinfo=timezone.utc)
LARGE, SMALL = 'sensor.frameos_touch_bigasuo_159k_1', 'sensor.frameos_touch_pf1007_qa'


class States:
    def __init__(self, entities):
        self.entities = entities

    def __getitem__(self, entity_id):
        return self.entities.get(entity_id)


def frame(minutes_ago, name):
    return SimpleNamespace(last_updated=NOW - timedelta(minutes=minutes_ago), attributes={'frame': name})


def render(template, entities=None, **context):
    env = Environment(extensions=['jinja2.ext.loopcontrols'])
    states = States(entities or {})
    env.globals.update(now=lambda: NOW, timedelta=timedelta, states=states,
                       state_attr=lambda entity, key: (states[entity].attributes.get(key) if states[entity] else None))
    return env.from_string(template).render(**context).strip()


class TouchHealthPackageTest(unittest.TestCase):
    sensor = PACKAGE['template'][0]['binary_sensor'][0]
    automations = {a['id']: a for a in PACKAGE['automation']}

    def test_reporting_is_on_while_every_known_frame_reported_in_the_last_20_minutes(self):
        self.assertEqual('True', render(self.sensor['state'], {LARGE: frame(4, 'bigasuo-159k-1')}))
        self.assertEqual('True', render(self.sensor['state'], {}), 'no frame has reported since HA started')

    def test_a_frame_silent_for_20_minutes_turns_it_off_and_is_named(self):
        entities = {LARGE: frame(25, 'bigasuo-159k-1'), SMALL: frame(3, 'pf1007-qa')}
        self.assertEqual('False', render(self.sensor['state'], entities))
        self.assertEqual('bigasuo-159k-1', render(self.sensor['attributes']['stale'], entities))

    def test_a_restart_alerts_but_an_update_does_not(self):
        condition = self.automations['frameos_touch_restarted']['conditions'][0]['value_template']
        before = SimpleNamespace(attributes={'app_started_at': '2026-10-17T20:00:00Z', 'installed_at': 'A', 'screen': 'HOME'})
        restarted = SimpleNamespace(attributes={'app_started_at': '2026-10-17T21:00:00Z', 'installed_at': 'A'})
        updated = SimpleNamespace(attributes={'app_started_at': '2026-10-17T21:00:00Z', 'installed_at': 'B'})
        self.assertEqual('True', render(condition, trigger=SimpleNamespace(from_state=before, to_state=restarted)))
        self.assertEqual('False', render(condition, trigger=SimpleNamespace(from_state=before, to_state=updated)))
        self.assertEqual('False', render(condition, trigger=SimpleNamespace(from_state=None, to_state=restarted)))

    def test_every_alert_goes_through_the_household_notifier_and_covers_both_frames(self):
        for automation in self.automations.values():
            self.assertEqual('script.notify_household', automation['actions'][0]['action'])
            self.assertIn(automation['actions'][0]['data']['severity'], {'info', 'warning', 'critical'})
        for key in ('frameos_touch_restarted', 'frameos_touch_low_memory'):
            self.assertEqual({LARGE, SMALL}, set(self.automations[key]['triggers'][0]['entity_id']))
        self.assertEqual(120, self.automations['frameos_touch_low_memory']['triggers'][0]['below'])


if __name__ == '__main__':
    unittest.main()
