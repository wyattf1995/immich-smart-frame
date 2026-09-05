#!/usr/bin/env python3
"""Publish bounded NAS health evidence to one Home Assistant state sensor."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler

ENTITY_ID = "sensor.frame_server_health"
STATE_ENDPOINT = "/api/states/" + ENTITY_ID
MAX_WATCHDOG_OUTPUT = 8192
MAX_INSPECT_OUTPUT = 512
MAX_EVIDENCE_BYTES = 16384
MAX_TOKEN_BYTES = 8192
WATCHDOG_TIMEOUT_SECONDS = 15
INSPECT_TIMEOUT_SECONDS = 5
PUBLISH_TIMEOUT_SECONDS = 5
CONTAINERS = (
    ("immich-kiosk", "kiosk"),
    ("birdnet-go", "birdnet"),
    ("nest-audio-bridge", "bridge"),
)
FRESH_REPORT = re.compile(r"^INFO: audio health fresh \(([0-9]{1,6})s <= ([0-9]{1,6})s\)$")
STALE_REPORT = re.compile(r"^CRITICAL: audio health is stale \(([0-9]{1,6})s exceeds [0-9]{1,6}s\)$")
INSPECT_REPORT = re.compile(r"^(true|false)\|(healthy|unhealthy|starting|none)\|(true|false)\|([0-9]{1,12})$")
CONTAINER_REPORT = re.compile(r"^INFO: (birdnet-go|nest-audio-bridge) RestartCount=([0-9]+|not-created)$")
DISK_REPORT = re.compile(r"^INFO: disk [0-9]+ KiB available on /[^\s]+$")


class TokenError(RuntimeError):
    pass


class PublishError(RuntimeError):
    pass


class WebhookError(RuntimeError):
    pass


class EvidenceError(RuntimeError):
    pass


class RefuseRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


NO_REDIRECT_OPENER = build_opener(RefuseRedirect())


def stop_process_group(process: subprocess.Popen[bytes]) -> None:
    group_signals_available = True
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except PermissionError:
        group_signals_available = False
        process.terminate()
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass
    # The leader may exit on TERM while a forked descendant ignores it. Always
    # send KILL to the session after the grace period, even when wait succeeded.
    try:
        if group_signals_available:
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except (PermissionError, ProcessLookupError):
        pass
    try:
        process.wait()
    except ChildProcessError:
        pass


def bounded_run(arguments: list[str], timeout: int, limit: int) -> tuple[int | None, str | None]:
    """Run a fixed command while incrementally bounding stdout and reaping it."""
    try:
        process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError:
        return None, None
    assert process.stdout is not None
    output = bytearray()
    deadline = time.monotonic() + timeout
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                stop_process_group(process)
                return process.returncode, None
            for key, _ in selector.select(remaining):
                chunk = os.read(key.fileobj.fileno(), min(4096, limit + 1 - len(output)))
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                output.extend(chunk)
                if len(output) > limit:
                    stop_process_group(process)
                    return process.returncode, None
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            stop_process_group(process)
            return process.returncode, None
        returncode = process.wait(timeout=remaining)
    except (OSError, subprocess.TimeoutExpired):
        stop_process_group(process)
        return process.returncode, None
    finally:
        selector.close()
        process.stdout.close()
    try:
        return returncode, output.decode("utf-8")
    except UnicodeDecodeError:
        return returncode, None


def parse_watchdog(returncode: int | None, output: str | None, now_ms: int) -> tuple[bool | None, int | None]:
    if output is None or len(output) > MAX_WATCHDOG_OUTPUT:
        return None, None
    lines = output.splitlines()
    if not lines:
        return None, None
    sources_healthy = lines.count("INFO: every reported audio source is HEALTHY") == 1
    fresh = [match for line in lines if (match := FRESH_REPORT.fullmatch(line))]
    stale = [match for line in lines if (match := STALE_REPORT.fullmatch(line))]
    containers = [match for line in lines if (match := CONTAINER_REPORT.fullmatch(line))]
    disks = [line for line in lines if DISK_REPORT.fullmatch(line)]
    allowed = {"INFO: every reported audio source is HEALTHY"}
    allowed.update(match.group(0) for match in fresh + stale + containers)
    allowed.update(disks)
    if (any(line not in allowed for line in lines) or len(containers) != 2 or
            {match.group(1) for match in containers} != {"birdnet-go", "nest-audio-bridge"} or len(disks) != 1):
        return None, None
    if len(fresh) == 1 and not stale and sources_healthy and returncode == 0:
        age, maximum = (int(value) for value in fresh[0].groups())
        if age <= maximum:
            return True, now_ms - age * 1000
    if len(stale) == 1 and not fresh and sources_healthy and returncode not in (None, 0):
        return False, now_ms - int(stale[0].group(1)) * 1000
    return None, None


def watchdog_audio(watchdog: str, now_ms: int) -> tuple[bool | None, int | None]:
    returncode, output = bounded_run([watchdog], WATCHDOG_TIMEOUT_SECONDS, MAX_WATCHDOG_OUTPUT)
    return parse_watchdog(returncode, output, now_ms)


def parse_container(returncode: int | None, output: str | None) -> dict[str, Any] | None:
    if returncode != 0 or output is None or len(output) > MAX_INSPECT_OUTPUT:
        return None
    match = INSPECT_REPORT.fullmatch(output.strip())
    if not match:
        return None
    running, health, oom, restarts = match.groups()
    oom_value = oom == "true"
    healthy = running == "true" and not oom_value and health in ("healthy", "none")
    return {"healthy": healthy, "oom": oom_value, "restarts": int(restarts)}


def container_status(container: str) -> dict[str, Any] | None:
    command = [
        "docker", "inspect", "--format",
        "{{.State.Running}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.OOMKilled}}|{{.RestartCount}}",
        container,
    ]
    returncode, output = bounded_run(command, INSPECT_TIMEOUT_SECONDS, MAX_INSPECT_OUTPUT)
    return parse_container(returncode, output)


def make_payload(observed_at: int, audio_healthy: bool | None, audio_last_at: int | None,
                 statuses: list[dict[str, Any] | None]) -> dict[str, Any]:
    attributes: dict[str, Any] = {
        "observedAt": observed_at,
        "audioLastAt": audio_last_at,
        "audioHealthy": audio_healthy is True,
    }
    for (_, prefix), status_value in zip(CONTAINERS, statuses, strict=True):
        attributes[f"{prefix}Restarts"] = None if status_value is None else status_value["restarts"]
        attributes[f"{prefix}Healthy"] = False if status_value is None else status_value["healthy"]
        attributes[f"{prefix}Oom"] = None if status_value is None else status_value["oom"]
    if audio_healthy is None or any(status_value is None for status_value in statuses):
        state = "unknown"
    elif audio_healthy and all(status_value["healthy"] for status_value in statuses if status_value is not None):
        state = "healthy"
    else:
        state = "degraded"
    return {"state": state, "attributes": attributes}


def collect_payload(watchdog: str, now_ms: int | None = None) -> dict[str, Any]:
    observed_at = int(time.time() * 1000) if now_ms is None else now_ms
    audio_healthy, audio_last_at = watchdog_audio(watchdog, observed_at)
    statuses = [container_status(container) for container, _ in CONTAINERS]
    return make_payload(observed_at, audio_healthy, audio_last_at, statuses)


def evidence_field(value: Any, limit: int) -> tuple[int | None, str | None]:
    if not isinstance(value, dict) or type(value.get("returncode")) is not int or not isinstance(value.get("output"), str):
        return None, None
    output = value["output"]
    if len(output.encode("utf-8")) > limit:
        return None, None
    return value["returncode"], output


def payload_from_evidence(evidence: Any) -> dict[str, Any]:
    if not isinstance(evidence, dict) or type(evidence.get("observedAt")) is not int:
        raise EvidenceError("evidence is invalid")
    observed_at = evidence["observedAt"]
    if not 0 <= observed_at <= 10**16 or not isinstance(evidence.get("containers"), dict):
        raise EvidenceError("evidence is invalid")
    watchdog_code, watchdog_output = evidence_field(evidence.get("watchdog"), MAX_WATCHDOG_OUTPUT)
    audio_healthy, audio_last_at = parse_watchdog(watchdog_code, watchdog_output, observed_at)
    statuses = []
    for container, _ in CONTAINERS:
        code, output = evidence_field(evidence["containers"].get(container), MAX_INSPECT_OUTPUT)
        statuses.append(parse_container(code, output))
    return make_payload(observed_at, audio_healthy, audio_last_at, statuses)


def read_private_file(secret_file: Path, error_type: type[RuntimeError]) -> str:
    flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW
    try:
        descriptor = os.open(secret_file, flags)
    except OSError as error:
        raise error_type("private file is unavailable") from error
    try:
        metadata = os.fstat(descriptor)
        if (not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) != 0o600 or
                metadata.st_uid not in (os.geteuid(), 0) or metadata.st_size <= 0 or
                metadata.st_size > MAX_TOKEN_BYTES):
            raise error_type("private file permissions are unsafe")
        token_bytes = os.read(descriptor, MAX_TOKEN_BYTES + 1)
    except OSError as error:
        raise error_type("private file is unreadable") from error
    finally:
        os.close(descriptor)
    if len(token_bytes) > MAX_TOKEN_BYTES:
        raise error_type("private file size is invalid")
    try:
        token = token_bytes.decode("utf-8").rstrip("\n")
    except UnicodeDecodeError as error:
        raise error_type("private file contents are invalid") from error
    if not token or "\n" in token or "\r" in token:
        raise error_type("private file contents are invalid")
    return token


def read_token(token_file: Path) -> str:
    return read_private_file(token_file, TokenError)


def read_webhook_id(webhook_file: Path) -> str:
    webhook_id = read_private_file(webhook_file, WebhookError)
    if not re.fullmatch(r"[0-9a-f]{64}", webhook_id):
        raise WebhookError("webhook identifier is invalid")
    return webhook_id


def state_url(ha_url: str) -> str:
    parsed = urlsplit(ha_url)
    try:
        port = parsed.port
    except ValueError as error:
        raise PublishError("Home Assistant URL is invalid") from error
    if (parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or
            parsed.password or parsed.path not in ("", "/") or parsed.query or parsed.fragment or
            (port is not None and not 1 <= port <= 65535)):
        raise PublishError("Home Assistant URL is invalid")
    return ha_url.rstrip("/") + STATE_ENDPOINT


def publish(ha_url: str, token_file: Path, payload: dict[str, Any]) -> None:
    token = read_token(token_file)
    request = Request(state_url(ha_url), data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
                      headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"}, method="POST")
    try:
        with NO_REDIRECT_OPENER.open(request, timeout=PUBLISH_TIMEOUT_SECONDS) as response:
            if response.status not in (200, 201):
                raise PublishError("Home Assistant state update was rejected")
            response.read(1024)
    except (HTTPError, URLError, OSError) as error:
        raise PublishError("Home Assistant state update failed") from error


def publish_webhook(ha_url: str, webhook_file: Path, payload: dict[str, Any]) -> None:
    webhook_id = read_webhook_id(webhook_file)
    request = Request(state_url(ha_url).replace(STATE_ENDPOINT, "/api/webhook/" + webhook_id),
                      data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
                      headers={"Content-Type": "application/json"}, method="POST")
    try:
        with NO_REDIRECT_OPENER.open(request, timeout=PUBLISH_TIMEOUT_SECONDS) as response:
            if response.status not in (200, 201):
                raise PublishError("Home Assistant webhook update was rejected")
            response.read(1024)
    except (HTTPError, URLError, OSError) as error:
        raise PublishError("Home Assistant webhook update failed") from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--watchdog")
    source.add_argument("--evidence-stdin", action="store_true")
    parser.add_argument("--ha-url", required=True)
    transport = parser.add_mutually_exclusive_group(required=True)
    transport.add_argument("--token-file")
    transport.add_argument("--webhook-id-file")
    arguments = parser.parse_args(argv)
    try:
        if arguments.evidence_stdin:
            raw = sys.stdin.buffer.read(MAX_EVIDENCE_BYTES + 1)
            if len(raw) > MAX_EVIDENCE_BYTES:
                raise EvidenceError("evidence is too large")
            payload = payload_from_evidence(json.loads(raw))
        else:
            payload = collect_payload(arguments.watchdog)
        if arguments.webhook_id_file:
            publish_webhook(arguments.ha_url, Path(arguments.webhook_id_file), payload)
        else:
            publish(arguments.ha_url, Path(arguments.token_file), payload)
    except (EvidenceError, TokenError, WebhookError, PublishError, json.JSONDecodeError):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
