#!/usr/bin/env python3
"""Read-only, privacy-preserving observer for Frame companion photo state."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import math
import secrets
import sqlite3
import time
from pathlib import Path
from typing import Any


DEFAULT_DURATION_SECONDS = 24 * 60 * 60
MAX_DURATION_SECONDS = 24 * 60 * 60
DEFAULT_SAMPLE_SECONDS = 2.0
MIN_SAMPLE_SECONDS = 1.0
MAX_SAMPLE_SECONDS = 60.0
DEFAULT_STALE_SECONDS = 180
MAX_STALE_SECONDS = 3600
# Match the companion's online state: a device is present for 90 seconds after poll.
DEVICE_ONLINE_MILLIS = 90_000
_MISSING = object()


def _integer(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _token(secret: bytes, namespace: bytes, value: str) -> bytes:
    return hmac.new(secret, namespace + value.encode("utf-8"), hashlib.sha256).digest()[:16]


def _percentile(values: list[int], percentile: float) -> int:
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * percentile) - 1]


class VarietyAggregate:
    """Aggregates sequence statistics without retaining identifiers in output."""

    def __init__(self, *, secret: bytes | None = None, stale_millis: int = DEFAULT_STALE_SECONDS * 1000) -> None:
        self._secret = secret or secrets.token_bytes(32)
        self._stale_millis = stale_millis
        self._sequences: dict[bytes, dict[str, Any]] = {}
        self._repeat_distances: list[int] = []
        self._counts = {
            "readFailures": 0,
            "invalidRows": 0,
            "ignoredNonPhotos": 0,
            "ignoredPaused": 0,
            "ignoredOffline": 0,
            "ignoredDeviceOffline": 0,
            "ignoredStale": 0,
            "ignoredFutureTimestamp": 0,
            "ignoredSameAsset": 0,
            "observations": 0,
            "uniqueObservations": 0,
            "repeatObservations": 0,
        }

    def read_failed(self) -> None:
        self._counts["readFailures"] += 1

    def accept(self, device: Any, status: Any, now_millis: int, *, seen_at: Any = _MISSING) -> None:
        if not isinstance(device, str) or not isinstance(status, dict):
            self._counts["invalidRows"] += 1
            return
        # The default exists for pure aggregation tests; production always supplies the DB field.
        seen = now_millis if seen_at is _MISSING else _integer(seen_at)
        if seen is None:
            self._counts["invalidRows"] += 1
            return
        if status.get("mode") != "photos":
            self._counts["ignoredNonPhotos"] += 1
            return
        if status.get("photosPaused") is True:
            self._counts["ignoredPaused"] += 1
            return
        if status.get("offline") is True:
            self._counts["ignoredOffline"] += 1
            return
        asset_id = status.get("currentAssetId")
        photo_at = _integer(status.get("lastPhotoAt"))
        if not isinstance(asset_id, str) or not asset_id or photo_at is None or photo_at < 0:
            self._counts["invalidRows"] += 1
            return
        if photo_at > now_millis or seen > now_millis:
            self._counts["ignoredFutureTimestamp"] += 1
            return
        if now_millis - seen > DEVICE_ONLINE_MILLIS:
            self._counts["ignoredDeviceOffline"] += 1
            return
        if photo_at < now_millis - self._stale_millis:
            self._counts["ignoredStale"] += 1
            return

        profile = status.get("profile") if isinstance(status.get("profile"), str) else ""
        sequence_key = _token(self._secret, b"sequence\0", device + "\0" + profile)
        asset_token = _token(self._secret, b"asset\0", asset_id)
        sequence = self._sequences.setdefault(sequence_key, {"last": None, "ordinal": 0, "seen": set(), "last_seen": {}})
        if sequence["last"] == asset_token:
            self._counts["ignoredSameAsset"] += 1
            return

        sequence["ordinal"] += 1
        ordinal = sequence["ordinal"]
        previous = sequence["last_seen"].get(asset_token)
        if previous is None:
            sequence["seen"].add(asset_token)
            self._counts["uniqueObservations"] += 1
        else:
            self._counts["repeatObservations"] += 1
            self._repeat_distances.append(ordinal - previous)
        sequence["last_seen"][asset_token] = ordinal
        sequence["last"] = asset_token
        self._counts["observations"] += 1

    def report(self, *, duration_seconds: int, sample_seconds: float) -> dict[str, int]:
        observations = self._counts["observations"]
        unique = self._counts["uniqueObservations"]
        return {
            "schema": 1,
            "durationSeconds": duration_seconds,
            "sampleMilliseconds": int(sample_seconds * 1000),
            "staleMilliseconds": self._stale_millis,
            "deviceOnlineMilliseconds": DEVICE_ONLINE_MILLIS,
            "readFailures": self._counts["readFailures"],
            "invalidRows": self._counts["invalidRows"],
            "ignoredNonPhotos": self._counts["ignoredNonPhotos"],
            "ignoredPaused": self._counts["ignoredPaused"],
            "ignoredOffline": self._counts["ignoredOffline"],
            "ignoredDeviceOffline": self._counts["ignoredDeviceOffline"],
            "ignoredStale": self._counts["ignoredStale"],
            "ignoredFutureTimestamp": self._counts["ignoredFutureTimestamp"],
            "ignoredSameAsset": self._counts["ignoredSameAsset"],
            "observations": observations,
            "uniqueObservations": unique,
            "uniqueRatioPpm": round(unique * 1_000_000 / observations) if observations else 0,
            "repeatObservations": self._counts["repeatObservations"],
            "repeatDistanceMin": min(self._repeat_distances, default=0),
            "repeatDistanceP50": _percentile(self._repeat_distances, 0.50),
            "repeatDistanceP95": _percentile(self._repeat_distances, 0.95),
            "sequences": len(self._sequences),
        }


def read_status_rows(database: Path) -> list[tuple[str, dict[str, Any], Any]]:
    """Read the companion's latest status snapshots without opening a write transaction."""
    uri = database.resolve().as_uri() + "?mode=ro"
    with sqlite3.connect(uri, uri=True, timeout=0) as connection:
        connection.execute("PRAGMA query_only = ON")
        connection.execute("PRAGMA busy_timeout = 0")
        rows = connection.execute("SELECT id, status, seen FROM devices").fetchall()
    parsed: list[tuple[str, dict[str, Any], Any]] = []
    for device, raw_status, seen in rows:
        try:
            status = json.loads(raw_status)
        except (TypeError, ValueError):
            status = None
        parsed.append((device, status, seen))
    return parsed


def _duration(value: str) -> int:
    try:
        seconds = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("duration must be a whole number") from error
    if not 1 <= seconds <= MAX_DURATION_SECONDS:
        raise argparse.ArgumentTypeError(f"duration must be 1..{MAX_DURATION_SECONDS} seconds")
    return seconds


def _sample(value: str) -> float:
    try:
        seconds = float(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("sample interval must be numeric") from error
    if not MIN_SAMPLE_SECONDS <= seconds <= MAX_SAMPLE_SECONDS:
        raise argparse.ArgumentTypeError(f"sample interval must be {MIN_SAMPLE_SECONDS:g}..{MAX_SAMPLE_SECONDS:g} seconds")
    return seconds


def _stale(value: str) -> int:
    try:
        seconds = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("stale interval must be a whole number") from error
    if not 1 <= seconds <= MAX_STALE_SECONDS:
        raise argparse.ArgumentTypeError(f"stale interval must be 1..{MAX_STALE_SECONDS} seconds")
    return seconds


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Aggregate Frame companion presentation variety without emitting identifiers.")
    parser.add_argument("database", type=Path, help="Companion SQLite state.db path; opened SQLite mode=ro")
    parser.add_argument("--duration-seconds", type=_duration, default=DEFAULT_DURATION_SECONDS, help="bounded run length, default 86400")
    parser.add_argument("--sample-seconds", type=_sample, default=DEFAULT_SAMPLE_SECONDS, help="read interval, default 2")
    parser.add_argument("--stale-seconds", type=_stale, default=DEFAULT_STALE_SECONDS, help="ignore old photo state, default 180")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    aggregate = VarietyAggregate(stale_millis=args.stale_seconds * 1000)
    deadline = time.monotonic() + args.duration_seconds
    while True:
        try:
            rows = read_status_rows(args.database)
        except (OSError, sqlite3.Error):
            aggregate.read_failed()
        else:
            now_millis = int(time.time() * 1000)
            for device, status, seen_at in rows:
                aggregate.accept(device, status, now_millis, seen_at=seen_at)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(args.sample_seconds, remaining))
    print(json.dumps(aggregate.report(duration_seconds=args.duration_seconds, sample_seconds=args.sample_seconds), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
