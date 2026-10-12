#!/usr/bin/env python3
"""Prove the immutable Frame Photos bridge packaging ledger rejects stale versions.

Run from a FrameOS checkout root (or pass that root as the sole argument) in
the NAS Android runtime.  This runner never contacts a device or server.  It
mutates only manifest.json temporarily, restores it byte-for-byte, and keeps
the focused Gradle logs and JUnit XML in reports/frameos-legacy.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else Path.cwd().resolve()
FRAME = ROOT / "frameos"
MANIFEST = FRAME / "app/src/main/assets/frame-photo-bridge/manifest.json"
LEDGER = FRAME / "app/src/test/resources/frame-photo-bridge/packaging-ledger.properties"
TEST_SOURCE = FRAME / "app/src/test/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridgePackagingContractTest.kt"
REPORT = ROOT / "reports/frameos-legacy/photo-bridge-packaging-proof"
RESULTS = FRAME / "app/build/test-results/testDebugUnitTest"
SUITE = "TEST-com.wyattfleming.frameos.photos.FramePhotoBridgePackagingContractTest.xml"
TEST_NAME = "built in extension version identifies its complete packaged asset set"
COMMAND = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest", "--tests", "com.wyattfleming.frameos.photos.FramePhotoBridgePackagingContractTest",
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label: str, receipt: dict) -> tuple[dict, list[dict]]:
    if RESULTS.exists():
        shutil.rmtree(RESULTS)
    log = REPORT / f"{label}.log"
    started = time.monotonic()
    with log.open("w", encoding="utf-8") as stream:
        process = subprocess.run(COMMAND, cwd=FRAME, stdout=stream, stderr=subprocess.STDOUT)
    xmls = sorted(RESULTS.rglob("TEST-*.xml")) if RESULTS.exists() else []
    if [path.name for path in xmls] != [SUITE]:
        raise RuntimeError(f"{label} produced unexpected JUnit XML")
    copied: list[dict] = []
    for source in xmls:
        target = REPORT / f"{label}-{source.name}"
        shutil.copy2(source, target)
        root = ET.parse(target).getroot()
        cases = list(root.iter("testcase"))
        failures = [case.find("failure") for case in cases if case.find("failure") is not None]
        errors = [case.find("error") for case in cases if case.find("error") is not None]
        skipped = [case.find("skipped") for case in cases if case.find("skipped") is not None]
        copied.append({
            "path": str(target.relative_to(ROOT)), "sha256": digest(target),
            "tests": len(cases), "failure_names": [case.attrib.get("name", "") for case in cases if case.find("failure") is not None],
            "failure_types": [failure.attrib.get("type", "") for failure in failures],
            "errors": len(errors), "skipped": len(skipped),
        })
    record = {"exit": process.returncode, "seconds": round(time.monotonic() - started, 3),
              "command": COMMAND, "log": str(log.relative_to(ROOT)), "log_sha256": digest(log), "junit": copied}
    receipt["runs"][label] = record
    return record, copied


def summary(xml: list[dict]) -> tuple[int, int, int, int]:
    return (sum(item["tests"] for item in xml), sum(len(item["failure_names"]) for item in xml),
            sum(item["errors"] for item in xml), sum(item["skipped"] for item in xml))


def main() -> int:
    for required in (MANIFEST, LEDGER, TEST_SOURCE):
        if not required.is_file():
            raise RuntimeError(f"required source missing: {required}")
    if REPORT.exists():
        raise RuntimeError("existing proof directory requires review")
    REPORT.mkdir(parents=True)
    original = MANIFEST.read_bytes()
    before = b'"version": "1.0.7"'
    after = b'"version": "1.0.6"'
    if original.count(before) != 1:
        raise RuntimeError("expected exactly one version mutation target")
    receipt = {
        "proof": "frame-photo-bridge-packaging-v1", "status": "started", "runs": {},
        "manifest_original_sha256": hashlib.sha256(original).hexdigest(),
        "ledger_sha256": digest(LEDGER), "test_source_sha256": digest(TEST_SOURCE),
        "expected_test_name": TEST_NAME,
    }
    MANIFEST.write_bytes(original.replace(before, after, 1))
    try:
        red, red_xml = run("version-revert-red", receipt)
        tests, failures, errors, skipped = summary(red_xml)
        types = [entry for item in red_xml for entry in item["failure_types"]]
        names = [entry for item in red_xml for entry in item["failure_names"]]
        if red["exit"] == 0 or (tests, failures, errors, skipped) != (1, 1, 0, 0) or names != [TEST_NAME] or types != ["org.junit.ComparisonFailure"]:
            raise RuntimeError("version revert did not produce the intended single ComparisonFailure")
    finally:
        MANIFEST.write_bytes(original)
    if MANIFEST.read_bytes() != original:
        raise RuntimeError("manifest did not restore byte-for-byte")
    green, green_xml = run("restored-green", receipt)
    if green["exit"] != 0 or summary(green_xml) != (1, 0, 0, 0):
        raise RuntimeError("restored packaging contract is not green")
    receipt.update({"status": "passed", "passed": True, "manifest_restored_sha256": digest(MANIFEST), "manifest_restored_exact": True})
    (REPORT / "receipt.json").write_text(json.dumps(receipt, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": "passed", "proof": receipt["proof"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
