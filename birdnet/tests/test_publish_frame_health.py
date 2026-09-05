#!/usr/bin/env python3
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts" / "publish-frame-health.py"
spec = importlib.util.spec_from_file_location("publish_frame_health", SOURCE)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

FRESH = "\n".join((
    "INFO: every reported audio source is HEALTHY",
    "INFO: audio health fresh (7s <= 120s)",
    "INFO: birdnet-go RestartCount=0",
))
STALE = "\n".join((
    "INFO: every reported audio source is HEALTHY",
    "CRITICAL: audio health is stale (181s exceeds 120s)",
))
INSPECT_OK = "true|healthy|false|2\n"


class Completed:
    def __init__(self, code=0, output=""):
        self.returncode = code
        self.stdout = output


def command_results(watchdog=Completed(0, FRESH), containers=None):
    containers = containers or [Completed(0, INSPECT_OK)] * 3
    return iter([watchdog, *containers])


class PublisherTests(unittest.TestCase):
    def test_fresh_fixed_reports_publish_only_the_fixed_sensor(self):
        responses = command_results()
        captured = {}

        def run(*args, **kwargs):
            return next(responses)

        def urlopen(request, timeout):
            captured["url"] = request.full_url
            captured["body"] = json.loads(request.data)
            captured["auth"] = request.get_header("Authorization")
            return type("Response", (), {"status": 200, "read": lambda self, limit: b"{}", "__enter__": lambda self: self, "__exit__": lambda *a: None})()

        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("private-token")
            token.chmod(0o600)
            with patch.object(publisher.subprocess, "run", side_effect=run), patch.object(publisher, "urlopen", side_effect=urlopen):
                payload = publisher.collect_payload("/watchdog", now_ms=1_000_000)
                publisher.publish("http://ha.local", token, payload)

        self.assertEqual(captured["url"], "http://ha.local/api/states/sensor.frame_server_health")
        self.assertEqual(captured["auth"], "Bearer private-token")
        self.assertEqual(captured["body"]["state"], "healthy")
        self.assertTrue(captured["body"]["attributes"]["audioHealthy"])
        self.assertEqual(captured["body"]["attributes"]["audioLastAt"], 993000)
        self.assertEqual(captured["body"]["attributes"]["kioskRestarts"], 2)

    def test_stale_audio_is_degraded_with_a_bounded_derived_timestamp(self):
        responses = command_results(Completed(1, STALE))
        with patch.object(publisher.subprocess, "run", side_effect=lambda *a, **k: next(responses)):
            payload = publisher.collect_payload("/watchdog", now_ms=1_000_000)
        self.assertEqual(payload["state"], "degraded")
        self.assertFalse(payload["attributes"]["audioHealthy"])
        self.assertEqual(payload["attributes"]["audioLastAt"], 819000)

    def test_missing_watchdog_data_is_unknown_never_healthy(self):
        responses = command_results(Completed(1, "CRITICAL: endpoint unavailable"))
        with patch.object(publisher.subprocess, "run", side_effect=lambda *a, **k: next(responses)):
            payload = publisher.collect_payload("/watchdog", now_ms=1_000_000)
        self.assertEqual(payload["state"], "unknown")
        self.assertFalse(payload["attributes"]["audioHealthy"])
        self.assertIsNone(payload["attributes"]["audioLastAt"])

    def test_failed_inspect_is_unknown_and_never_marks_container_healthy(self):
        responses = command_results(containers=[Completed(0, INSPECT_OK), Completed(1, "private daemon detail"), Completed(0, INSPECT_OK)])
        with patch.object(publisher.subprocess, "run", side_effect=lambda *a, **k: next(responses)):
            payload = publisher.collect_payload("/watchdog", now_ms=1_000_000)
        self.assertEqual(payload["state"], "unknown")
        self.assertFalse(payload["attributes"]["birdnetHealthy"])
        self.assertIsNone(payload["attributes"]["birdnetRestarts"])

    def test_insecure_token_mode_is_rejected_without_returning_its_value(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("do-not-disclose")
            token.chmod(0o644)
            with self.assertRaises(publisher.TokenError) as raised:
                publisher.read_token(token)
        self.assertNotIn("do-not-disclose", str(raised.exception))

    def test_http_failure_is_reported_without_a_token(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("do-not-disclose")
            token.chmod(0o600)
            error = HTTPError("http://ha.local/api/states/sensor.frame_server_health", 503, "unavailable", None, io.BytesIO(b"private response"))
            with patch.object(publisher, "urlopen", side_effect=error):
                with self.assertRaises(publisher.PublishError) as raised:
                    publisher.publish("http://ha.local", token, {"state": "unknown", "attributes": {}})
        self.assertNotIn("do-not-disclose", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
