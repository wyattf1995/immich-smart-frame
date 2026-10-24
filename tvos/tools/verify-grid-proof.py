#!/usr/bin/env python3
"""Prove favorites-grid tests catch a broken partial-row downward navigation guard."""

from __future__ import annotations

import datetime as dt
import json
import subprocess
import sys
import xml.etree.ElementTree as et
from pathlib import Path


ROOT = Path.cwd()
SOURCE = ROOT / "app/src/main/java/com/wyattfleming/frametv/FavoriteGridNavigation.java"
RESULTS = ROOT / "app/build/test-results/testDebugUnitTest"
VALIDATION = ROOT / "validation"
TEST_CLASS = "com.wyattfleming.frametv.FavoriteGridNavigationTest"
GRADLE = ["../frameos/gradlew", "-p", "../frameos", "--no-daemon", "--max-workers=2", ":frametv:testDebugUnitTest"]


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


def make_partial_row_unsafe(source: bytes) -> bytes:
    old = b"return Math.min(targetStart + index % columns, count - 1);"
    if source.count(old) != 1:
        raise RuntimeError("the expected partial-row return was not found exactly once")
    return source.replace(old, b"return -1;")


def combine_logs(paths: tuple[Path, ...], destination: Path) -> None:
    with destination.open("wb") as combined:
        for path in paths:
            combined.write(("\n===== " + path.name + " =====\n").encode("utf-8"))
            if path.exists():
                combined.write(path.read_bytes())


def main() -> int:
    if not SOURCE.is_file() or not (ROOT.parent / "frameos/gradlew").is_file():
        print("Run from the tvos directory, beside frameos/ (Frame TV builds there as :frametv).", file=sys.stderr)
        return 2
    VALIDATION.mkdir(exist_ok=True)
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    initial_log = VALIDATION / ("grid-proof-initial-" + stamp + ".log")
    negative_log = VALIDATION / ("grid-proof-negative-" + stamp + ".log")
    final_log = VALIDATION / ("grid-proof-final-" + stamp + ".log")
    receipt = VALIDATION / "grid-proof.json"
    combined_log = VALIDATION / "grid-proof.log"
    original = SOURCE.read_bytes()
    outcome = {"timestamp_utc": stamp, "source": str(SOURCE.relative_to(ROOT))}
    try:
        outcome["initial_full_test_exit"] = run(GRADLE, initial_log)
        if outcome["initial_full_test_exit"] != 0:
            raise RuntimeError("initial full unit test run was not green")

        SOURCE.write_bytes(make_partial_row_unsafe(original))
        outcome["negative_targeted_test_exit"] = run(
                GRADLE + ["--tests", TEST_CLASS], negative_log)
        outcome["negative_test_failures"] = actual_failures()
        if outcome["negative_targeted_test_exit"] == 0:
            raise RuntimeError("unsafe partial-row source unexpectedly passed the targeted test")
        if outcome["negative_test_failures"] < 1:
            raise RuntimeError("negative run did not record an actual grid test failure")
    finally:
        SOURCE.write_bytes(original)
        outcome["source_restored_exactly"] = SOURCE.read_bytes() == original
        outcome["final_full_test_exit"] = run(GRADLE, final_log)
        outcome["passed"] = (
            outcome.get("initial_full_test_exit") == 0
            and outcome.get("negative_targeted_test_exit") != 0
            and outcome.get("negative_test_failures", 0) >= 1
            and outcome["source_restored_exactly"]
            and outcome["final_full_test_exit"] == 0
        )
        logs = (initial_log, negative_log, final_log)
        combine_logs(logs, combined_log)
        outcome["logs"] = [str(path.relative_to(ROOT)) for path in (*logs, combined_log)]
        receipt.write_text(json.dumps(outcome, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(outcome, indent=2, sort_keys=True))
    return 0 if outcome["passed"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.SubprocessError, et.ParseError) as error:
        print("grid proof failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
