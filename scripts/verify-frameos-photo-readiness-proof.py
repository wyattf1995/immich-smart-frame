#!/usr/bin/env python3
"""Run source-only red/green proofs for Photos observation readiness.

Run from a FrameOS checkout root, normally in the NAS Android runtime:
  python3 scripts/verify-frameos-photo-readiness-proof.py

The runner restores every mutated source byte-for-byte even when a proof fails.
It never contacts a device, server, or NAS itself.
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


ROOT = Path.cwd()
FRAME = ROOT / "frameos"
REPORT = ROOT / "reports" / "frameos-legacy"
ARTIFACTS = REPORT / "photo-readiness-proof-artifacts"
KOTLIN_TESTS = (
    "com.wyattfleming.frameos.web.FrameWebPhotosReadinessStateTest",
    "com.wyattfleming.frameos.web.FrameWebSurfaceContractTest",
)
KOTLIN_COMMAND = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    *[item for test in KOTLIN_TESTS for item in ("--tests", test)],
]
JS_COMMAND = ["node", "--test", "app/src/test/js/photos-playback.test.js"]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError(f"required file is missing: {path.relative_to(ROOT)}")


def run(label: str, command: list[str], cwd: Path, proof: dict) -> dict:
    log = ARTIFACTS / f"{label}.log"
    started = time.time()
    with log.open("w") as stream:
        completed = subprocess.run(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
    record = {
        "command": command,
        "cwd": str(cwd.relative_to(ROOT)),
        "exit": completed.returncode,
        "seconds": round(time.time() - started, 3),
        "log": str(log.relative_to(ROOT)),
        "log_sha256": sha256(log),
    }
    proof["runs"][label] = record
    return record


def copy_junit(label: str, proof: dict) -> dict:
    source_dir = FRAME / "app/build/test-results/testDebugUnitTest"
    copies = []
    for source in sorted(source_dir.rglob("TEST-*.xml")):
        destination = ARTIFACTS / f"{label}-{source.name}"
        shutil.copy2(source, destination)
        root = ET.parse(destination).getroot()
        testcases = list(root.iter("testcase"))
        failures = [case.attrib.get("name", "") for case in testcases if case.find("failure") is not None]
        errors = [case.attrib.get("name", "") for case in testcases if case.find("error") is not None]
        copies.append({
            "path": str(destination.relative_to(ROOT)),
            "sha256": sha256(destination),
            "tests": len(testcases),
            "failures": failures,
            "errors": errors,
        })
    if not copies:
        raise RuntimeError(f"{label} produced no JUnit XML")
    proof["junit"][label] = copies
    return {
        "tests": sum(item["tests"] for item in copies),
        "failures": sum(len(item["failures"]) for item in copies),
        "errors": sum(len(item["errors"]) for item in copies),
        "cases": {case for item in copies for case in item["failures"] + item["errors"]},
    }


def require_green(label: str, record: dict, summary: dict) -> None:
    if record["exit"] != 0 or summary["failures"] or summary["errors"]:
        raise RuntimeError(f"{label} was not green: {record['exit']=}, {summary=}")


def replace_once(path: Path, original: bytes, before: str, after: str) -> None:
    text = original.decode("utf-8")
    if text.count(before) != 1:
        raise RuntimeError(f"expected exactly one mutation target in {path.relative_to(ROOT)}: {before!r}")
    path.write_text(text.replace(before, after, 1), encoding="utf-8")


def kotlin_proof(
    name: str,
    path: Path,
    original: bytes,
    before: str,
    after: str,
    expected_failure: str,
    proof: dict,
) -> None:
    replace_once(path, original, before, after)
    try:
        red = run(f"{name}-red", KOTLIN_COMMAND, FRAME, proof)
        red_summary = copy_junit(f"{name}-red", proof)
        if red["exit"] == 0 or expected_failure not in red_summary["cases"]:
            raise RuntimeError(f"{name} did not fail the expected test {expected_failure!r}: {red_summary}")
    finally:
        path.write_bytes(original)
    restored = sha256(path)
    if path.read_bytes() != original:
        raise RuntimeError(f"{name} did not restore {path.relative_to(ROOT)} exactly")
    green = run(f"{name}-restored-green", KOTLIN_COMMAND, FRAME, proof)
    green_summary = copy_junit(f"{name}-restored-green", proof)
    require_green(f"{name} restored", green, green_summary)
    proof["mutations"][name] = {
        "source": str(path.relative_to(ROOT)),
        "original_sha256": hashlib.sha256(original).hexdigest(),
        "restored_sha256": restored,
        "expected_failure": expected_failure,
        "red_tests": red_summary["tests"],
        "red_failures": red_summary["failures"],
        "restored_tests": green_summary["tests"],
    }


def js_proof(path: Path, original: bytes, proof: dict) -> None:
    before = 'reportDiagnostic("htmx_no_content", inspected.fields);'
    after = 'reportDiagnostic("htmx_error", inspected.fields);'
    expected_failure = "a no-content Kiosk response keeps capture closed until a later settled response"
    baseline = run("js-baseline-green", JS_COMMAND, FRAME, proof)
    if baseline["exit"] != 0:
        raise RuntimeError("JavaScript Photos baseline was not green")
    replace_once(path, original, before, after)
    try:
        red = run("js-204-stage-red", JS_COMMAND, FRAME, proof)
        if red["exit"] == 0 or expected_failure not in (ARTIFACTS / "js-204-stage-red.log").read_text(errors="replace"):
            raise RuntimeError("204 diagnostic mutation did not fail the intended Node test")
    finally:
        path.write_bytes(original)
    restored = sha256(path)
    if path.read_bytes() != original:
        raise RuntimeError("JavaScript Photos source was not restored exactly")
    green = run("js-204-stage-restored-green", JS_COMMAND, FRAME, proof)
    if green["exit"] != 0:
        raise RuntimeError("JavaScript Photos restored source was not green")
    proof["mutations"]["js_204_diagnostic"] = {
        "source": str(path.relative_to(ROOT)),
        "original_sha256": hashlib.sha256(original).hexdigest(),
        "restored_sha256": restored,
        "expected_failure": expected_failure,
        "red_exit": red["exit"],
        "restored_exit": green["exit"],
    }


def main() -> int:
    state = FRAME / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebPhotosReadinessState.kt"
    surface = FRAME / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt"
    javascript = FRAME / "app/src/main/assets/frame-photo-bridge/photos.js"
    test_paths = [
        FRAME / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebPhotosReadinessStateTest.kt",
        FRAME / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebSurfaceContractTest.kt",
        FRAME / "app/src/test/js/photos-playback.test.js",
    ]
    for path in [FRAME / "gradlew", state, surface, javascript, *test_paths]:
        require(path)
    REPORT.mkdir(parents=True, exist_ok=True)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    originals = {state: state.read_bytes(), surface: surface.read_bytes(), javascript: javascript.read_bytes()}
    proof = {
        "passed": False,
        "runner": str(Path(__file__).relative_to(ROOT)),
        "runner_sha256": sha256(Path(__file__)),
        "test_sha256": {str(path.relative_to(ROOT)): sha256(path) for path in test_paths},
        "runs": {},
        "junit": {},
        "mutations": {},
    }
    receipt = REPORT / "photo-readiness-proof.json"
    try:
        initial = run("kotlin-baseline-green", KOTLIN_COMMAND, FRAME, proof)
        initial_summary = copy_junit("kotlin-baseline-green", proof)
        require_green("Kotlin baseline", initial, initial_summary)
        kotlin_proof(
            "old-generation", state, originals[state],
            "if (expectedGeneration != generation) return false",
            "if (expectedGeneration == null) return false",
            "a new request rejects its old observation and requires a new one", proof,
        )
        kotlin_proof(
            "outer-paint-gate", surface, originals[surface],
            "if (!hasCurrentPhotoObservation()) {",
            "if (false) {",
            "render readiness requires both a successful stop and contentful paint", proof,
        )
        js_proof(javascript, originals[javascript], proof)
        if proof["test_sha256"] != {str(path.relative_to(ROOT)): sha256(path) for path in test_paths}:
            raise RuntimeError("a guarding test changed during proof")
        proof["restored_source_sha256"] = {str(path.relative_to(ROOT)): sha256(path) for path in originals}
        proof["passed"] = True
    except Exception as error:
        proof["error"] = repr(error)
        raise
    finally:
        for path, original in originals.items():
            path.write_bytes(original)
        proof["final_restored_source_sha256"] = {str(path.relative_to(ROOT)): sha256(path) for path in originals}
        receipt.write_text(json.dumps(proof, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Photos readiness source-only proof recorded in {receipt.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
