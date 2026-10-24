#!/usr/bin/env python3
"""Prove UrlPolicyTest rejects an intentionally unsafe navigation implementation.

Run this from the tvos directory on the approved build host.  It changes only the
body of UrlPolicy.navigationAllowed(), restores its exact original bytes in a
finally block, and leaves timestamped logs and a JSON receipt in validation/.
"""

from __future__ import annotations

import datetime as dt
import json
import subprocess
import sys
import xml.etree.ElementTree as et
from pathlib import Path


ROOT = Path.cwd()
SOURCE = ROOT / "app/src/main/java/com/wyattfleming/frametv/UrlPolicy.java"
RESULTS = ROOT / "app/build/test-results/testDebugUnitTest"
VALIDATION = ROOT / "validation"
TEST_CLASS = "com.wyattfleming.frametv.UrlPolicyTest"
GRADLE = ["../frameos/gradlew", "-p", "../frameos", "--no-daemon", "--max-workers=2", ":frametv:testDebugUnitTest"]


def run(command: list[str], log_path: Path) -> int:
    with log_path.open("wb") as log:
        process = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    return process.returncode


def navigation_body_bounds(source: bytes) -> tuple[int, int]:
    marker = b"public static boolean navigationAllowed(String configured, String destination)"
    start = source.find(marker)
    if start == -1:
        raise RuntimeError("navigationAllowed signature was not found")
    open_brace = source.find(b"{", start)
    if open_brace == -1:
        raise RuntimeError("navigationAllowed has no body")
    depth = 0
    for index in range(open_brace, len(source)):
        if source[index:index + 1] == b"{":
            depth += 1
        elif source[index:index + 1] == b"}":
            depth -= 1
            if depth == 0:
                return open_brace + 1, index
    raise RuntimeError("navigationAllowed body is not balanced")


def make_cross_origin_unsafe(source: bytes) -> bytes:
    body_start, body_end = navigation_body_bounds(source)
    body = source[body_start:body_end]
    old = (
        b"return !lower(from.getHost()).isEmpty()\n"
        b"                    && lower(from.getScheme()).equals(lower(to.getScheme()))\n"
        b"                    && lower(from.getHost()).equals(lower(to.getHost()))\n"
        b"                    && effectivePort(from) == effectivePort(to);"
    )
    if body.count(old) != 1:
        raise RuntimeError("the expected same-origin guard was not found exactly once")
    unsafe_body = body.replace(old, b"return true;")
    return source[:body_start] + unsafe_body + source[body_end:]


def make_credential_key_unsafe(source: bytes) -> bytes:
    old = b"key.contains(\"apikey\") || key.equals(\"key\")"
    if source.count(old) != 1:
        raise RuntimeError("the expected credential-key guard was not found exactly once")
    return source.replace(old, b"key.equals(\"apikey\")")


def failing_test_count() -> int:
    count = 0
    for report in RESULTS.glob("TEST-*.xml"):
        root = et.parse(report).getroot()
        if root.attrib.get("name") != TEST_CLASS:
            continue
        count += int(root.attrib.get("failures", "0")) + int(root.attrib.get("errors", "0"))
    return count


def log_tail(path: Path, byte_limit: int = 4096) -> str:
    data = path.read_bytes()[-byte_limit:]
    return data.decode("utf-8", errors="replace")


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
    receipt = VALIDATION / "source-proof.json"
    combined_log = VALIDATION / "source-proof.log"
    initial_log = VALIDATION / ("url-policy-source-proof-initial-" + stamp + ".log")
    origin_negative_log = VALIDATION / ("url-policy-source-proof-origin-negative-" + stamp + ".log")
    credential_negative_log = VALIDATION / ("url-policy-source-proof-credential-negative-" + stamp + ".log")
    final_log = VALIDATION / ("url-policy-source-proof-final-" + stamp + ".log")
    outcome = {"timestamp_utc": stamp, "source": str(SOURCE.relative_to(ROOT))}
    original = SOURCE.read_bytes()
    restored = False
    try:
        outcome["initial_full_test_exit"] = run(GRADLE, initial_log)
        if outcome["initial_full_test_exit"] != 0:
            raise RuntimeError("initial full unit test run was not green; tail follows:\n"
                               + log_tail(initial_log))

        SOURCE.write_bytes(make_cross_origin_unsafe(original))
        outcome["origin_negative_targeted_test_exit"] = run(
                GRADLE + ["--tests", TEST_CLASS], origin_negative_log)
        outcome["origin_negative_test_failures"] = failing_test_count()
        if outcome["origin_negative_targeted_test_exit"] == 0:
            raise RuntimeError("unsafe navigation source unexpectedly passed the targeted test")
        if outcome["origin_negative_test_failures"] < 1:
            raise RuntimeError("negative run did not record an actual UrlPolicyTest failure")
        SOURCE.write_bytes(original)

        SOURCE.write_bytes(make_credential_key_unsafe(original))
        outcome["credential_negative_targeted_test_exit"] = run(
                GRADLE + ["--tests", TEST_CLASS], credential_negative_log)
        outcome["credential_negative_test_failures"] = failing_test_count()
        if outcome["credential_negative_targeted_test_exit"] == 0:
            raise RuntimeError("unsafe credential-key source unexpectedly passed the targeted test")
        if outcome["credential_negative_test_failures"] < 1:
            raise RuntimeError("credential negative run did not record an actual UrlPolicyTest failure")
    finally:
        SOURCE.write_bytes(original)
        restored = SOURCE.read_bytes() == original
        outcome["source_restored_exactly"] = restored
        outcome["final_full_test_exit"] = run(GRADLE, final_log)
        outcome["passed"] = (
            outcome.get("initial_full_test_exit") == 0
            and outcome.get("origin_negative_targeted_test_exit") != 0
            and outcome.get("origin_negative_test_failures", 0) >= 1
            and outcome.get("credential_negative_targeted_test_exit") != 0
            and outcome.get("credential_negative_test_failures", 0) >= 1
            and restored
            and outcome["final_full_test_exit"] == 0
        )
        step_logs = (initial_log, origin_negative_log, credential_negative_log, final_log)
        combine_logs(step_logs, combined_log)
        outcome["logs"] = [str(path.relative_to(ROOT)) for path in (*step_logs, combined_log)]
        receipt.write_text(json.dumps(outcome, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(outcome, indent=2, sort_keys=True))
    return 0 if outcome["passed"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.SubprocessError, et.ParseError) as error:
        print("source-proof failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
