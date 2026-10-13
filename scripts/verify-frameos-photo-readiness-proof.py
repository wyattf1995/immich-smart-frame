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
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else Path.cwd().resolve()
FRAME = ROOT / "frameos"
REPORT = ROOT / "reports" / "frameos-legacy"
ARTIFACTS = REPORT / "photo-readiness-proof-artifacts"
KOTLIN_TESTS = (
    "com.wyattfleming.frameos.web.FrameWebPhotosReadinessStateTest",
    "com.wyattfleming.frameos.web.FrameWebSurfaceContractTest",
    "com.wyattfleming.frameos.MainActivityPhotoReadinessContractTest",
)
KOTLIN_COMMAND = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    *[item for test in KOTLIN_TESTS for item in ("--tests", test)],
]
JS_COMMAND = ["node", "--test", "--test-reporter=tap", "app/src/test/js/photos-playback.test.js"]
EXPECTED_JUNIT_SUITES = {
    "TEST-com.wyattfleming.frameos.web.FrameWebPhotosReadinessStateTest.xml": 3,
    "TEST-com.wyattfleming.frameos.web.FrameWebSurfaceContractTest.xml": 16,
    "TEST-com.wyattfleming.frameos.MainActivityPhotoReadinessContractTest.xml": 1,
}
EXPECTED_KOTLIN_TESTS = sum(EXPECTED_JUNIT_SUITES.values())
EXPECTED_JS_TESTS = 30


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(path: Path) -> None:
    if not path.is_file():
        raise RuntimeError(f"required file is missing: {path.relative_to(ROOT)}")


def run(label: str, command: list[str], cwd: Path, proof: dict) -> dict:
    if command == KOTLIN_COMMAND:
        results = FRAME / "app/build/test-results/testDebugUnitTest"
        if results.exists():
            shutil.rmtree(results)
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
    sources = sorted(source_dir.rglob("TEST-*.xml"))
    if {source.name for source in sources} != set(EXPECTED_JUNIT_SUITES):
        raise RuntimeError(f"{label} produced unexpected JUnit suites: {[source.name for source in sources]}")
    for source in sources:
        destination = ARTIFACTS / f"{label}-{source.name}"
        shutil.copy2(source, destination)
        root = ET.parse(destination).getroot()
        testcases = list(root.iter("testcase"))
        failures = [case.attrib.get("name", "") for case in testcases if case.find("failure") is not None]
        errors = [case.attrib.get("name", "") for case in testcases if case.find("error") is not None]
        skipped = [case.attrib.get("name", "") for case in testcases if case.find("skipped") is not None]
        failure_details = [
            {
                "name": case.attrib.get("name", ""),
                "type": failure.attrib.get("type", ""),
                "message": failure.attrib.get("message", ""),
            }
            for case in testcases for failure in case.findall("failure")
        ]
        if len(testcases) != EXPECTED_JUNIT_SUITES[source.name]:
            raise RuntimeError(f"{label} had unexpected test count in {source.name}: {len(testcases)}")
        copies.append({
            "path": str(destination.relative_to(ROOT)),
            "sha256": sha256(destination),
            "tests": len(testcases),
            "failures": failures,
            "errors": errors,
            "skipped": skipped,
            "failure_details": failure_details,
        })
    if not copies:
        raise RuntimeError(f"{label} produced no JUnit XML")
    proof["junit"][label] = copies
    return {
        "tests": sum(item["tests"] for item in copies),
        "failures": sum(len(item["failures"]) for item in copies),
        "errors": sum(len(item["errors"]) for item in copies),
        "skipped": sum(len(item["skipped"]) for item in copies),
        "cases": {case for item in copies for case in item["failures"] + item["errors"]},
        "failure_details": [detail for item in copies for detail in item["failure_details"]],
    }


def require_green(label: str, record: dict, summary: dict) -> None:
    if (
        record["exit"] != 0 or summary["tests"] != EXPECTED_KOTLIN_TESTS or
        summary["failures"] or summary["errors"] or summary["skipped"]
    ):
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
    expected_failures: set[str],
    proof: dict,
) -> None:
    replace_once(path, original, before, after)
    try:
        red = run(f"{name}-red", KOTLIN_COMMAND, FRAME, proof)
        red_summary = copy_junit(f"{name}-red", proof)
        if (
            red["exit"] == 0 or red_summary["tests"] != EXPECTED_KOTLIN_TESTS or
            red_summary["failures"] != len(expected_failures) or red_summary["errors"] or red_summary["skipped"] or
            red_summary["cases"] != expected_failures or
            any(detail["type"] != "java.lang.AssertionError" for detail in red_summary["failure_details"])
        ):
            raise RuntimeError(f"{name} did not fail exactly the expected tests {expected_failures!r}: {red_summary}")
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
        "expected_failures": sorted(expected_failures),
        "red_tests": red_summary["tests"],
        "red_failures": red_summary["failures"],
        "restored_tests": green_summary["tests"],
    }


def tap_counts(log: Path) -> dict:
    text = log.read_text(errors="replace")
    counts = {}
    for name in ("tests", "pass", "fail"):
        match = re.search(rf"^# {name} (\d+)$", text, re.MULTILINE)
        if not match:
            raise RuntimeError(f"Node TAP log does not contain {name} count")
        counts[name] = int(match.group(1))
    counts["text"] = text
    return counts


def js_proof(path: Path, original: bytes, proof: dict) -> None:
    before = 'if (mutation.type !== "attributes" || !["src", "value", "class", "style"].includes(mutation.attributeName)) return false;'
    after = 'if (mutation.type !== "attributes" || !["src", "value", "class"].includes(mutation.attributeName)) return false;'
    expected_failure = "capture ignores progress bar RAF styles but keeps Kiosk visibility mutations"
    baseline = run("js-baseline-green", JS_COMMAND, FRAME, proof)
    baseline_counts = tap_counts(ARTIFACTS / "js-baseline-green.log")
    if baseline["exit"] != 0 or {key: baseline_counts[key] for key in ("tests", "pass", "fail")} != {"tests": EXPECTED_JS_TESTS, "pass": EXPECTED_JS_TESTS, "fail": 0}:
        raise RuntimeError("JavaScript Photos baseline was not green")
    replace_once(path, original, before, after)
    try:
        red = run("js-mutation-filter-stage-red", JS_COMMAND, FRAME, proof)
        red_counts = tap_counts(ARTIFACTS / "js-mutation-filter-stage-red.log")
        if (
            red["exit"] == 0 or expected_failure not in red_counts["text"] or
            {key: red_counts[key] for key in ("tests", "pass", "fail")} != {"tests": EXPECTED_JS_TESTS, "pass": EXPECTED_JS_TESTS - 1, "fail": 1}
        ):
            raise RuntimeError("capture mutation filter mutation did not fail the intended Node test")
    finally:
        path.write_bytes(original)
    restored = sha256(path)
    if path.read_bytes() != original:
        raise RuntimeError("JavaScript Photos source was not restored exactly")
    green = run("js-mutation-filter-stage-restored-green", JS_COMMAND, FRAME, proof)
    green_counts = tap_counts(ARTIFACTS / "js-mutation-filter-stage-restored-green.log")
    if green["exit"] != 0 or {key: green_counts[key] for key in ("tests", "pass", "fail")} != {"tests": EXPECTED_JS_TESTS, "pass": EXPECTED_JS_TESTS, "fail": 0}:
        raise RuntimeError("JavaScript Photos restored source was not green")
    proof["mutations"]["js_capture_mutation_filter"] = {
        "source": str(path.relative_to(ROOT)),
        "original_sha256": hashlib.sha256(original).hexdigest(),
        "restored_sha256": restored,
        "expected_failure": expected_failure,
        "red_exit": red["exit"],
        "restored_exit": green["exit"],
        "baseline_counts": {key: baseline_counts[key] for key in ("tests", "pass", "fail")},
        "red_counts": {key: red_counts[key] for key in ("tests", "pass", "fail")},
        "restored_counts": {key: green_counts[key] for key in ("tests", "pass", "fail")},
    }


def main() -> int:
    state = FRAME / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebPhotosReadinessState.kt"
    surface = FRAME / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt"
    javascript = FRAME / "app/src/main/assets/frame-photo-bridge/photos.js"
    test_paths = [
        FRAME / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebPhotosReadinessStateTest.kt",
        FRAME / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebSurfaceContractTest.kt",
        FRAME / "app/src/test/js/photos-playback.test.js",
        FRAME / "app/src/test/kotlin/com/wyattfleming/frameos/MainActivityPhotoReadinessContractTest.kt",
    ]
    for path in [FRAME / "gradlew", state, surface, javascript, *test_paths]:
        require(path)
    REPORT.mkdir(parents=True, exist_ok=True)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    activity = FRAME / "app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt"
    require(activity)
    originals = {
        state: state.read_bytes(),
        surface: surface.read_bytes(),
        javascript: javascript.read_bytes(),
        activity: activity.read_bytes(),
    }
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
            {
                "a new request rejects its old observation and requires a new one",
                "outer paint cannot qualify Photos until the current bridge generation is observed",
            }, proof,
        )
        kotlin_proof(
            "outer-paint-gate", surface, originals[surface],
            "if (!hasCurrentPhotoObservation()) {",
            "if (false) {",
            {"render readiness requires both a successful stop and contentful paint"}, proof,
        )
        kotlin_proof(
            "main-activity-ready-hook", activity, originals[activity],
            "webSurface?.onCurrentPhotoObserved(photo)",
            "Unit",
            {"only a current visible photo observation can satisfy web readiness"}, proof,
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
