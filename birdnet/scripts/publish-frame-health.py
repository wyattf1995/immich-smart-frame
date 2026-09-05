#!/usr/bin/env python3
"""Publish bounded NAS health evidence to one Home Assistant state sensor."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ENTITY_ID = "sensor.frame_server_health"
STATE_ENDPOINT = "/api/states/" + ENTITY_ID
MAX_WATCHDOG_OUTPUT = 8192
MAX_INSPECT_OUTPUT = 512
MAX_TOKEN_BYTES = 8192
WATCHDOG_TIMEOUT_SECONDS = 15
INSPECT_TIMEOUT_SECONDS = 5
PUBLISH_TIMEOUT_SECONDS = 5
CONTAINERS = (
    ("immich-kiosk", "kiosk"),
    ("birdnet-go", "birdnet"),
    ("nest-audio-bridge", "bridge"),
)
FRESH_REPORT = re.compile(r"^INFO: audio health fresh \(([0-9]{1,6})s <= [0-9]{1,6}s\)$")
STALE_REPORT = re.compile(r"^CRITICAL: audio health is stale \(([0-9]{1,6})s exceeds [0-9]{1,6}s\)$")
INSPECT_REPORT = re.compile(r"^(true|false)\|(healthy|unhealthy|starting|none)\|(true|false)\|([0-9]{1,12})$")


class TokenError(RuntimeError):
    pass


class PublishError(RuntimeError):
    pass


def bounded_run(arguments: list[str], timeout: int, limit: int) -> tuple[int | None, str | None]:
    try:
        result = subprocess.run(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None, None
    if len(result.stdout) > limit:
        return result.returncode, None
    return result.returncode, result.stdout


def watchdog_audio(watchdog: str, now_ms: int) -> tuple[bool | None, int | None]:
    returncode, output = bounded_run([watchdog], WATCHDOG_TIMEOUT_SECONDS, MAX_WATCHDOG_OUTPUT)
    if output is None:
        return None, None
    lines = output.splitlines()
    sources_healthy = lines.count("INFO: every reported audio source is HEALTHY") == 1
    fresh = [FRESH_REPORT.fullmatch(line) for line in lines]
    stale = [STALE_REPORT.fullmatch(line) for line in lines]
    fresh = [match for match in fresh if match]
    stale = [match for match in stale if match]
    if len(fresh) == 1 and not stale and sources_healthy and returncode == 0:
        age_ms = int(fresh[0].group(1)) * 1000
        return True, now_ms - age_ms
    if len(stale) == 1 and not fresh:
        age_ms = int(stale[0].group(1)) * 1000
        return False, now_ms - age_ms
    return None, None


def container_status(container: str) -> dict[str, Any] | None:
    command = [
        "docker", "inspect", "--format",
        "{{.State.Running}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.OOMKilled}}|{{.RestartCount}}",
        container,
    ]
    returncode, output = bounded_run(command, INSPECT_TIMEOUT_SECONDS, MAX_INSPECT_OUTPUT)
    if returncode != 0 or output is None:
        return None
    match = INSPECT_REPORT.fullmatch(output.strip())
    if not match:
        return None
    running, health, oom, restarts = match.groups()
    oom_value = oom == "true"
    # A container without a Docker healthcheck is healthy only when it is running
    # and has not OOM-killed; a defined healthcheck must report healthy.
    healthy = running == "true" and not oom_value and health in ("healthy", "none")
    return {"healthy": healthy, "oom": oom_value, "restarts": int(restarts)}


def collect_payload(watchdog: str, now_ms: int | None = None) -> dict[str, Any]:
    observed_at = int(time.time() * 1000) if now_ms is None else now_ms
    audio_healthy, audio_last_at = watchdog_audio(watchdog, observed_at)
    attributes: dict[str, Any] = {
        "observedAt": observed_at,
        "audioLastAt": audio_last_at,
        "audioHealthy": audio_healthy is True,
    }
    statuses: list[dict[str, Any] | None] = []
    for container, prefix in CONTAINERS:
        status_value = container_status(container)
        statuses.append(status_value)
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


def read_token(token_file: Path) -> str:
    try:
        metadata = token_file.stat()
    except OSError as error:
        raise TokenError("token file is unavailable") from error
    if stat.S_IMODE(metadata.st_mode) != 0o600 or metadata.st_uid not in (os.geteuid(), 0):
        raise TokenError("token file permissions are unsafe")
    if metadata.st_size <= 0 or metadata.st_size > MAX_TOKEN_BYTES:
        raise TokenError("token file size is invalid")
    try:
        token = token_file.read_text(encoding="utf-8")
    except OSError as error:
        raise TokenError("token file is unreadable") from error
    token = token.rstrip("\n")
    if not token or "\n" in token or "\r" in token:
        raise TokenError("token file contents are invalid")
    return token


def state_url(ha_url: str) -> str:
    parsed = urlsplit(ha_url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.username or parsed.password:
        raise PublishError("Home Assistant URL is invalid")
    return ha_url.rstrip("/") + STATE_ENDPOINT


def publish(ha_url: str, token_file: Path, payload: dict[str, Any]) -> None:
    token = read_token(token_file)
    request = Request(
        state_url(ha_url),
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=PUBLISH_TIMEOUT_SECONDS) as response:
            if response.status not in (200, 201):
                raise PublishError("Home Assistant state update was rejected")
            response.read(1024)
    except (HTTPError, URLError, OSError) as error:
        raise PublishError("Home Assistant state update failed") from error


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--watchdog", required=True)
    parser.add_argument("--ha-url", required=True)
    parser.add_argument("--token-file", required=True)
    arguments = parser.parse_args(argv)
    try:
        publish(arguments.ha_url, Path(arguments.token_file), collect_payload(arguments.watchdog))
    except (TokenError, PublishError):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
