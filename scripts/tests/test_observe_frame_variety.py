import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "observe-frame-variety.py"
SPEC = importlib.util.spec_from_file_location("observe_frame_variety", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


ASSET_A = "11111111-1111-1111-1111-111111111111"
ASSET_B = "22222222-2222-2222-2222-222222222222"
ASSET_C = "33333333-3333-3333-3333-333333333333"
NOW = 2_000_000


def row(device, asset, *, profile="balanced", mode="photos", offline=False, paused=False, photo_at=NOW):
    return device, {
        "mode": mode,
        "currentAssetId": asset,
        "profile": profile,
        "offline": offline,
        "photosPaused": paused,
        "lastPhotoAt": photo_at,
    }


class VarietyAggregationTest(unittest.TestCase):
    def test_unique_ratio_and_repeat_distances_only_count_changed_assets(self):
        aggregate = MODULE.VarietyAggregate(secret=b"x" * 32, stale_millis=180_000)
        for device, status in [
            row("living-room", ASSET_A),
            row("living-room", ASSET_A, photo_at=NOW + 1),  # receipt refresh, not a new observation
            row("living-room", ASSET_B, photo_at=NOW + 2),
            row("living-room", ASSET_A, photo_at=NOW + 3),
        ]:
            aggregate.accept(device, status, NOW + 4)

        report = aggregate.report(duration_seconds=60, sample_seconds=2)
        self.assertEqual(3, report["observations"])
        self.assertEqual(2, report["uniqueObservations"])
        self.assertEqual(666667, report["uniqueRatioPpm"])
        self.assertEqual(1, report["repeatObservations"])
        self.assertEqual(2, report["repeatDistanceMin"])
        self.assertEqual(2, report["repeatDistanceP50"])
        self.assertEqual(2, report["repeatDistanceP95"])
        self.assertEqual(1, report["sequences"])

    def test_profile_and_device_boundaries_do_not_create_cross_sequence_repeats(self):
        aggregate = MODULE.VarietyAggregate(secret=b"y" * 32, stale_millis=180_000)
        for device, status in [
            row("living-room", ASSET_A, profile="balanced"),
            row("living-room", ASSET_A, profile="photography", photo_at=NOW + 1),
            row("parents", ASSET_A, profile="balanced", photo_at=NOW + 2),
        ]:
            aggregate.accept(device, status, NOW + 3)

        report = aggregate.report(duration_seconds=60, sample_seconds=2)
        self.assertEqual(3, report["observations"])
        self.assertEqual(3, report["uniqueObservations"])
        self.assertEqual(0, report["repeatObservations"])
        self.assertEqual(3, report["sequences"])

    def test_paused_offline_stale_and_non_photo_rows_are_excluded(self):
        aggregate = MODULE.VarietyAggregate(secret=b"z" * 32, stale_millis=180_000)
        for device, status in [
            row("frame", ASSET_A),
            row("frame", ASSET_B, paused=True, photo_at=NOW + 1),
            row("frame", ASSET_B, offline=True, photo_at=NOW + 2),
            row("frame", ASSET_B, mode="weather", photo_at=NOW + 3),
            row("frame", ASSET_B, photo_at=NOW - 180_001),
            row("frame", ASSET_B, photo_at=NOW + 4),
        ]:
            aggregate.accept(device, status, NOW + 5)

        report = aggregate.report(duration_seconds=60, sample_seconds=2)
        self.assertEqual(2, report["observations"])
        self.assertEqual(1, report["ignoredPaused"])
        self.assertEqual(1, report["ignoredOffline"])
        self.assertEqual(1, report["ignoredNonPhotos"])
        self.assertEqual(1, report["ignoredStale"])

    def test_recent_photo_from_a_disconnected_device_is_excluded(self):
        aggregate = MODULE.VarietyAggregate(secret=b"d" * 32, stale_millis=180_000)
        device, status = row("frame", ASSET_A, photo_at=NOW)
        aggregate.accept(device, status, NOW, seen_at=NOW - MODULE.DEVICE_ONLINE_MILLIS - 1)

        report = aggregate.report(duration_seconds=60, sample_seconds=2)
        self.assertEqual(0, report["observations"])
        self.assertEqual(1, report["ignoredDeviceOffline"])

    def test_future_photo_or_device_timestamps_are_excluded(self):
        aggregate = MODULE.VarietyAggregate(secret=b"f" * 32, stale_millis=180_000)
        device, status = row("frame", ASSET_A, photo_at=NOW + 1)
        aggregate.accept(device, status, NOW, seen_at=NOW)
        device, status = row("frame", ASSET_B, photo_at=NOW)
        aggregate.accept(device, status, NOW, seen_at=NOW + 1)

        report = aggregate.report(duration_seconds=60, sample_seconds=2)
        self.assertEqual(0, report["observations"])
        self.assertEqual(2, report["ignoredFutureTimestamp"])

    def test_report_contains_no_identifiers_or_hashes(self):
        aggregate = MODULE.VarietyAggregate(secret=b"q" * 32, stale_millis=180_000)
        aggregate.accept("private-frame", row("private-frame", ASSET_A)[1], NOW + 1)
        rendered = json.dumps(aggregate.report(duration_seconds=60, sample_seconds=2), sort_keys=True)
        self.assertNotIn(ASSET_A, rendered)
        self.assertNotIn("private-frame", rendered)
        self.assertNotIn("balanced", rendered)
        self.assertTrue(all(isinstance(value, int) for value in json.loads(rendered).values()))


class ReadOnlySqliteTest(unittest.TestCase):
    def test_reading_status_uses_read_only_connection_and_never_modifies_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "state.db"
            with sqlite3.connect(path) as connection:
                connection.execute("CREATE TABLE devices (id TEXT PRIMARY KEY, status TEXT, seen INTEGER)")
                connection.execute(
                    "INSERT INTO devices VALUES (?, ?, ?)",
                    ("private-frame", json.dumps(row("private-frame", ASSET_A)[1]), NOW),
                )
                connection.commit()
            before = path.read_bytes()
            rows = MODULE.read_status_rows(path)
            after = path.read_bytes()

        self.assertEqual(before, after)
        self.assertEqual([("private-frame", row("private-frame", ASSET_A)[1], NOW)], rows)


if __name__ == "__main__":
    unittest.main()
