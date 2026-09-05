#!/usr/bin/env python3
import importlib.util
import io
import json
from pathlib import Path
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import tempfile
import threading
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
    return iter([(result.returncode, result.stdout) for result in [watchdog, *containers]])


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
            with patch.object(publisher, "bounded_run", side_effect=run), patch.object(publisher.NO_REDIRECT_OPENER, "open", side_effect=urlopen):
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
        with patch.object(publisher, "bounded_run", side_effect=lambda *a, **k: next(responses)):
            payload = publisher.collect_payload("/watchdog", now_ms=1_000_000)
        self.assertEqual(payload["state"], "degraded")
        self.assertFalse(payload["attributes"]["audioHealthy"])
        self.assertEqual(payload["attributes"]["audioLastAt"], 819000)

    def test_missing_watchdog_data_is_unknown_never_healthy(self):
        responses = command_results(Completed(1, "CRITICAL: endpoint unavailable"))
        with patch.object(publisher, "bounded_run", side_effect=lambda *a, **k: next(responses)):
            payload = publisher.collect_payload("/watchdog", now_ms=1_000_000)
        self.assertEqual(payload["state"], "unknown")
        self.assertFalse(payload["attributes"]["audioHealthy"])
        self.assertIsNone(payload["attributes"]["audioLastAt"])

    def test_failed_inspect_is_unknown_and_never_marks_container_healthy(self):
        responses = command_results(containers=[Completed(0, INSPECT_OK), Completed(1, "private daemon detail"), Completed(0, INSPECT_OK)])
        with patch.object(publisher, "bounded_run", side_effect=lambda *a, **k: next(responses)):
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

    def test_token_symlink_is_rejected_without_following_it(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "target"
            target.write_text("do-not-disclose")
            target.chmod(0o600)
            token = Path(directory) / "token"
            token.symlink_to(target)
            with self.assertRaises(publisher.TokenError) as raised:
                publisher.read_token(token)
        self.assertNotIn("do-not-disclose", str(raised.exception))

    def test_invalid_webhook_id_is_rejected_without_returning_its_value(self):
        with tempfile.TemporaryDirectory() as directory:
            webhook = Path(directory) / "webhook"
            webhook.write_text("not-a-webhook-id")
            webhook.chmod(0o600)
            with self.assertRaises(publisher.WebhookError) as raised:
                publisher.read_webhook_id(webhook)
        self.assertNotIn("not-a-webhook-id", str(raised.exception))

    def test_oversized_subprocess_output_is_reaped_and_unknown(self):
        code, output = publisher.bounded_run([sys.executable, "-c", "import sys; sys.stdout.write('x' * 8193)"], 5, 8192)
        self.assertIsNone(output)
        self.assertIsNotNone(code)

    def test_http_failure_is_reported_without_a_token(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("do-not-disclose")
            token.chmod(0o600)
            error = HTTPError("http://ha.local/api/states/sensor.frame_server_health", 503, "unavailable", None, io.BytesIO(b"private response"))
            with patch.object(publisher.NO_REDIRECT_OPENER, "open", side_effect=error):
                with self.assertRaises(publisher.PublishError) as raised:
                    publisher.publish("http://ha.local", token, {"state": "unknown", "attributes": {}})
            error.close()
        self.assertNotIn("do-not-disclose", str(raised.exception))

    def test_state_url_accepts_only_a_bare_origin(self):
        self.assertEqual(publisher.state_url("https://ha.local"), "https://ha.local/api/states/sensor.frame_server_health")
        for value in ("https://ha.local/path", "https://ha.local/?query", "https://ha.local/#fragment", "https://user:pass@ha.local", "https://ha.local:bad"):
            with self.subTest(value=value):
                with self.assertRaises(publisher.PublishError):
                    publisher.state_url(value)

    def test_redirect_never_reaches_another_origin_or_receives_a_token(self):
        target_requests = []

        class Target(BaseHTTPRequestHandler):
            def do_POST(self):
                target_requests.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)
        target_thread = threading.Thread(target=target.serve_forever, daemon=True)
        target_thread.start()

        class Redirect(BaseHTTPRequestHandler):
            def do_POST(self):
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{target.server_port}/other-origin")
                self.end_headers()

            def log_message(self, *args):
                pass

        redirect = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
        redirect_thread = threading.Thread(target=redirect.serve_forever, daemon=True)
        redirect_thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                token = Path(directory) / "token"
                token.write_text("do-not-forward")
                token.chmod(0o600)
                with self.assertRaises(publisher.PublishError) as raised:
                    publisher.publish(f"http://127.0.0.1:{redirect.server_port}", token, {"state": "unknown", "attributes": {}})
                if isinstance(raised.exception.__cause__, HTTPError):
                    raised.exception.__cause__.close()
        finally:
            redirect.shutdown(); target.shutdown()
            redirect.server_close(); target.server_close()
        self.assertEqual(target_requests, [])

    def test_webhook_uses_no_authorization_header_and_refuses_redirects(self):
        target_requests = []
        webhook_headers = []

        class Target(BaseHTTPRequestHandler):
            def do_POST(self):
                target_requests.append(dict(self.headers))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        target = ThreadingHTTPServer(("127.0.0.1", 0), Target)
        threading.Thread(target=target.serve_forever, daemon=True).start()

        class Redirect(BaseHTTPRequestHandler):
            def do_POST(self):
                webhook_headers.append(dict(self.headers))
                self.send_response(302)
                self.send_header("Location", f"http://127.0.0.1:{target.server_port}/other-origin")
                self.end_headers()

            def log_message(self, *args):
                pass

        redirect = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
        threading.Thread(target=redirect.serve_forever, daemon=True).start()
        webhook_id = "a" * 64
        try:
            with tempfile.TemporaryDirectory() as directory:
                webhook = Path(directory) / "webhook"
                webhook.write_text(webhook_id)
                webhook.chmod(0o600)
                with self.assertRaises(publisher.PublishError) as raised:
                    publisher.publish_webhook(f"http://127.0.0.1:{redirect.server_port}", webhook, {"state": "unknown", "attributes": {}})
                if isinstance(raised.exception.__cause__, HTTPError):
                    raised.exception.__cause__.close()
        finally:
            redirect.shutdown(); target.shutdown()
            redirect.server_close(); target.server_close()
        self.assertEqual(target_requests, [])
        self.assertNotIn("Authorization", webhook_headers[0])

    def test_stdin_evidence_uses_the_same_conservative_parser(self):
        payload = publisher.payload_from_evidence({
            "observedAt": 1_000_000,
            "watchdog": {"returncode": 1, "output": STALE},
            "containers": {name: {"returncode": 0, "output": INSPECT_OK} for name, _ in publisher.CONTAINERS},
        })
        self.assertEqual(payload["state"], "degraded")
        self.assertEqual(payload["attributes"]["audioLastAt"], 819000)


if __name__ == "__main__":
    unittest.main()
