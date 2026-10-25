"""Behavioral bounds and backward-compatible snapshot protocol; temporary DBs only."""
import importlib.util
import json
import sqlite3
import tempfile
import time
import tracemalloc
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('companion_perf', Path(__file__).resolve().parents[1] / 'server.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PerformanceContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'state.db'
        self.now = 1_800_000_000_000
        self.store = module.FrameStore(self.path, {'main': {}, 'other': {}}, ['balanced'], clock=lambda: self.now)

    def tearDown(self):
        self.store.close()
        self.tmp.cleanup()

    def poll(self, revision=None, **kwargs):
        return self.store.poll('main', {'status': {'mode': 'photos'}, 'knownSnapshotRevision': revision, **kwargs})

    def test_unchanged_omits_payload_but_records_status_and_avoids_hidden_query(self):
        revision = self.poll()['snapshotRevision']
        statements = []
        connect = sqlite3.connect
        def traced(*args, **kwargs):
            db = connect(*args, **kwargs)
            db.set_trace_callback(statements.append)
            return db
        with patch.object(module.sqlite3, 'connect', traced):
            response = self.poll(revision, status={'mode': 'home'})
        self.assertTrue(response['unchanged'])
        self.assertEqual(response['snapshotRevision'], revision)
        for field in ('settings', 'hiddenAssets', 'commands', 'events'):
            self.assertNotIn(field, response)
        self.assertFalse(any('FROM preferences' in sql for sql in statements), statements)
        self.assertFalse(any('DELETE FROM' in sql or 'OFFSET' in sql for sql in statements), statements)
        self.assertEqual(self.store.state()['devices'][0]['status']['mode'], 'home')

    def test_settings_and_each_preference_mutation_invalidate(self):
        revision = self.poll()['snapshotRevision']
        self.store.settings('main', {'idleReturnSeconds': 600})
        changed = self.poll(revision)
        self.assertNotIn('unchanged', changed)
        self.assertEqual(changed['settings']['idleReturnSeconds'], 600)
        asset = '11111111-1111-4111-8111-111111111111'
        for preference in ('hide', 'clear'):
            revision = changed['snapshotRevision']
            self.store.feedback('main', {'assetId': asset, 'preference': preference})
            changed = self.poll(revision)
            self.assertNotEqual(changed['snapshotRevision'], revision)
            self.assertEqual(changed['hiddenAssets'], [asset] if preference == 'hide' else [])

    def test_commands_acknowledgement_next_delivery_and_expiration_invalidate(self):
        revision = self.poll()['snapshotRevision']
        first = self.store.command('main', {'type': 'photo_next'})
        one = self.poll(revision)
        self.assertEqual(one['commands'][0]['id'], first['id'])
        second = self.store.command('main', {'type': 'photo_pause'})
        two = self.poll(one['snapshotRevision'], acks=[{'id': first['id'], 'status': 'applied'}])
        self.assertEqual(two['commands'][0]['id'], second['id'])
        self.now += 60001
        expired = self.poll(two['snapshotRevision'])
        self.assertEqual(expired['commands'], [])
        self.assertNotEqual(expired['snapshotRevision'], two['snapshotRevision'])

    def test_event_creation_expiration_and_enabled_state_invalidate(self):
        self.store.settings('main', {'eventOverlays': True})
        revision = self.poll()['snapshotRevision']
        event = self.store.event('main', {'type': 'calendar', 'text': 'Fixture', 'expiresInSeconds': 30})
        response = self.poll(revision)
        self.assertEqual(response['events'][0]['id'], event['id'])
        self.now += 30001
        expired = self.poll(response['snapshotRevision'])
        self.assertEqual(expired['events'], [])
        self.assertNotEqual(expired['snapshotRevision'], response['snapshotRevision'])
        self.store.settings('main', {'eventOverlays': False})
        self.assertNotIn('unchanged', self.poll(expired['snapshotRevision']))

    def test_old_clients_bad_revisions_cross_device_and_restart_receive_full_policy(self):
        full = self.poll()
        for revision in (None, '', '☃', '\ud800', [], {}, True, '0'*64, 'A'*64):
            self.assertIn('hiddenAssets', self.poll(revision))
        other = self.store.poll('other', {'knownSnapshotRevision': full['snapshotRevision']})
        self.assertIn('hiddenAssets', other)
        self.store = module.FrameStore(self.path, {}, ['balanced'], clock=lambda: self.now)
        self.assertIn('hiddenAssets', self.poll(full['snapshotRevision']))

    def test_readers_do_not_acquire_writer_lock(self):
        # WAL snapshot readers must work even while another writer is in flight.
        self.store.preferences_export = Path(self.tmp.name)/'preferences.json'
        with patch.object(module, 'EXPORT_RETRY_INITIAL_SECONDS', .01), self.store.db() as writer:
            writer.execute("UPDATE devices SET label='Uncommitted' WHERE id='main'")
            self.assertEqual(self.store.preferences('main'), [])
            started = time.monotonic()
            self.store.export_preferences()
            self.assertLess(time.monotonic() - started, .5)
        self.assertEqual(json.loads(self.store.preferences_export.read_text())['version'], 1)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            with self.store.db(write=False) as db:
                desired, published = self.store._export_revisions(db)
            if desired == published: break
            time.sleep(.01)
        self.assertEqual(desired, published)

    def test_poll_never_calls_global_maintenance_and_uses_device_expiry_index(self):
        with patch.object(self.store, '_expire', side_effect=AssertionError('global poll cleanup')):
            self.poll()
        with self.store.db(write=False) as db:
            plan = list(db.execute("EXPLAIN QUERY PLAN UPDATE commands SET status='expired' WHERE device=? AND status='queued' AND expires<=?", ('main',self.now)))
        self.assertTrue(any('queued_device_expiry' in row['detail'] for row in plan), plan)

    def test_history_pruning_keeps_live_queued_commands(self):
        self.poll()
        pending = self.store.command('main', {'type': 'photo_next'})
        with self.store.db() as db:
            db.executemany('INSERT INTO commands(id,device,payload,issued,expires,status) VALUES(?,?,?,?,?,?)',
                [(str(i), 'other', '{}', self.now+1+i, self.now+60000, 'applied') for i in range(1100)])
            self.store._expire(db)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM commands WHERE status!='queued'").fetchone()[0], 1000)
        self.assertEqual(self.poll()['commands'][0]['id'], pending['id'])

    def test_export_peak_memory_is_bounded_and_failure_is_atomic(self):
        with self.store.db() as db:
            for device in ('main','other'):
                db.executemany('INSERT INTO preferences VALUES(?,?,?,?)',
                    ((device, f'{i:08x}-1111-4111-8111-111111111111', 'hide', self.now) for i in range(1000)))
        self.store.preferences_export = Path(self.tmp.name)/'preferences.json'
        tracemalloc.start()
        try:
            self.store.export_preferences()
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertLess(peak, 256*1024, f'export allocated {peak} bytes for 2000 preferences')
        prior = self.store.preferences_export.read_bytes()
        data = json.loads(prior)
        self.assertEqual(len(data['devices']['main']['assets']), 1000)
        self.assertEqual(self.store.preferences_export.stat().st_mode & 0o777, 0o640)
        with patch.object(module.os, 'replace', side_effect=OSError('fixture failure')):
            with self.assertRaises(OSError): self.store.export_preferences()
        self.assertEqual(self.store.preferences_export.read_bytes(), prior)
        self.assertFalse(self.store.preferences_export.with_suffix('.tmp').exists())


if __name__ == '__main__': unittest.main()
