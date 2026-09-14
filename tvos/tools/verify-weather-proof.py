#!/usr/bin/env python3
"""Prove the cache source and local-day forecast guards have executable tests."""

from __future__ import annotations

import datetime as dt
import json
import subprocess
import sys
import xml.etree.ElementTree as et
from pathlib import Path

ROOT = Path.cwd()
SOURCE = ROOT / "app/src/main/java/com/wyattfleming/frametv/WeatherCachePolicy.java"
FORECAST_SOURCE = ROOT / "app/src/main/java/com/wyattfleming/frametv/WeatherForecast.java"
RESULTS = ROOT / "app/build/test-results/testDebugUnitTest"
VALIDATION = ROOT / "validation"
TEST_CLASS = "com.wyattfleming.frametv.WeatherForecastTest"
GRADLE = ["./gradlew", "--no-daemon", "--max-workers=2", ":app:testDebugUnitTest"]

def run(command: list[str], log_path: Path) -> int:
    with log_path.open("wb") as log:
        return subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT).returncode

def actual_failures() -> int:
    failures = 0
    for report in RESULTS.glob("TEST-*.xml"):
        root = et.parse(report).getroot()
        if root.attrib.get("name") == TEST_CLASS:
            failures += int(root.attrib.get("failures", "0")) + int(root.attrib.get("errors", "0"))
    return failures

def make_source_unsafe(source: bytes) -> bytes:
    old = b"if (!sourceKey.equals(cachedSource)) return false;"
    if source.count(old) != 1:
        raise RuntimeError("the expected cache-source guard was not found exactly once")
    return source.replace(old, b"// unsafe proof mutation: accepts a cached forecast from another location")

def make_day_filter_unsafe(source: bytes) -> bytes:
    old = b"localDay(day.epochSeconds).compareTo(today) < 0"
    if source.count(old) != 1:
        raise RuntimeError("the expected local-day filter was not found exactly once")
    return source.replace(old, b"false")

def combine_logs(paths: tuple[Path, ...], destination: Path) -> None:
    with destination.open("wb") as combined:
        for path in paths:
            combined.write(("\n===== " + path.name + " =====\n").encode("utf-8"))
            if path.exists(): combined.write(path.read_bytes())

def main() -> int:
    if not SOURCE.is_file() or not FORECAST_SOURCE.is_file() or not (ROOT / "gradlew").is_file():
        print("Run from the tvos directory containing gradlew and app/.", file=sys.stderr)
        return 2
    VALIDATION.mkdir(exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    initial_log = VALIDATION / ("weather-proof-initial-" + stamp + ".log")
    cache_negative_log = VALIDATION / ("weather-proof-cache-negative-" + stamp + ".log")
    day_negative_log = VALIDATION / ("weather-proof-day-negative-" + stamp + ".log")
    night_negative_log = VALIDATION / ("weather-proof-night-negative-" + stamp + ".log")
    final_log = VALIDATION / ("weather-proof-final-" + stamp + ".log")
    receipt, combined_log = VALIDATION / "weather-proof.json", VALIDATION / "weather-proof.log"
    original, forecast_original = SOURCE.read_bytes(), FORECAST_SOURCE.read_bytes()
    outcome = {"timestamp_utc": stamp, "sources": [str(SOURCE.relative_to(ROOT)), str(FORECAST_SOURCE.relative_to(ROOT))]}
    try:
        outcome["initial_full_test_exit"] = run(GRADLE, initial_log)
        if outcome["initial_full_test_exit"] != 0: raise RuntimeError("initial full unit test run was not green")
        SOURCE.write_bytes(make_source_unsafe(original))
        outcome["cache_negative_targeted_test_exit"] = run(GRADLE + ["--tests", TEST_CLASS + ".cacheRejectsAChangedLocationAndExpiredOrFutureSnapshots"], cache_negative_log)
        outcome["cache_negative_test_failures"] = actual_failures()
        if outcome["cache_negative_targeted_test_exit"] == 0: raise RuntimeError("unsafe cache source unexpectedly passed")
        if outcome["cache_negative_test_failures"] < 1: raise RuntimeError("cache negative run did not record a weather test failure")
        SOURCE.write_bytes(original)
        FORECAST_SOURCE.write_bytes(make_day_filter_unsafe(forecast_original))
        outcome["day_negative_targeted_test_exit"] = run(GRADLE + ["--tests", TEST_CLASS + ".dropsYesterdayWhenAReusableSnapshotCrossesLocalMidnight"], day_negative_log)
        outcome["day_negative_test_failures"] = actual_failures()
        if outcome["day_negative_targeted_test_exit"] == 0: raise RuntimeError("unsafe local-day filter unexpectedly passed")
        if outcome["day_negative_test_failures"] < 1: raise RuntimeError("day negative run did not record a weather test failure")
        FORECAST_SOURCE.write_bytes(forecast_original.replace(
            b"code != null && code == 0 && !Boolean.TRUE.equals(isDay)", b"false"))
        outcome["night_negative_targeted_test_exit"] = run(GRADLE + ["--tests", TEST_CLASS + ".clearHourlySymbolsRespectDaylightAndUnknownCacheData"], night_negative_log)
        outcome["night_negative_test_failures"] = actual_failures()
        if outcome["night_negative_targeted_test_exit"] == 0 or outcome["night_negative_test_failures"] < 1:
            raise RuntimeError("night-symbol negative run did not record a weather test failure")
    finally:
        SOURCE.write_bytes(original)
        FORECAST_SOURCE.write_bytes(forecast_original)
        outcome["source_restored_exactly"] = SOURCE.read_bytes() == original and FORECAST_SOURCE.read_bytes() == forecast_original
        outcome["final_full_test_exit"] = run(GRADLE, final_log)
        outcome["passed"] = (outcome.get("initial_full_test_exit") == 0
            and outcome.get("cache_negative_targeted_test_exit") != 0 and outcome.get("cache_negative_test_failures", 0) >= 1
            and outcome.get("day_negative_targeted_test_exit") != 0 and outcome.get("day_negative_test_failures", 0) >= 1
            and outcome.get("night_negative_targeted_test_exit") != 0 and outcome.get("night_negative_test_failures", 0) >= 1
            and outcome["source_restored_exactly"] and outcome["final_full_test_exit"] == 0)
        logs = (initial_log, cache_negative_log, day_negative_log, night_negative_log, final_log)
        combine_logs(logs, combined_log)
        outcome["logs"] = [str(path.relative_to(ROOT)) for path in (*logs, combined_log)]
        receipt.write_text(json.dumps(outcome, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(outcome, indent=2, sort_keys=True))
    return 0 if outcome["passed"] else 1

if __name__ == "__main__":
    try: raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.SubprocessError, et.ParseError) as error:
        print("weather proof failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
