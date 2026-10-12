#!/usr/bin/env python3
"""Prove touchscreen jitter and immersive-edge policy by source-only red/green runs."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree

if not __debug__:
    raise RuntimeError("optimized Python is not permitted for proof validation")
if len(sys.argv) != 2:
    raise SystemExit(f"usage: {Path(sys.argv[0]).name} <repository-root>")

root = Path(sys.argv[1]).resolve()
frame = root / "frameos"
out = root / "reports/frameos-legacy/gesture-reliability-proof"
out.mkdir(parents=True, exist_ok=True)
report_dir = frame / "app/build/test-results/testDebugUnitTest"
suites = {
    "classifier": {
        "source": frame / "app/src/main/kotlin/com/wyattfleming/frameos/navigation/TouchEdgeSwipeClassifier.kt",
        "test": frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/TouchEdgeSwipeClassifierTest.kt",
        "class": "com.wyattfleming.frameos.navigation.TouchEdgeSwipeClassifierTest",
        "result": "TEST-com.wyattfleming.frameos.navigation.TouchEdgeSwipeClassifierTest.xml",
    },
    "router": {
        "source": frame / "app/src/main/kotlin/com/wyattfleming/frameos/navigation/TouchModeNavigationRouter.kt",
        "test": frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/TouchModeNavigationRouterTest.kt",
        "class": "com.wyattfleming.frameos.navigation.TouchModeNavigationRouterTest",
        "result": "TEST-com.wyattfleming.frameos.navigation.TouchModeNavigationRouterTest.xml",
    },
    "policy": {
        "source": frame / "app/src/main/kotlin/com/wyattfleming/frameos/ui/FrameTouchInputLayout.kt",
        "test": frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/TouchEdgeSwipePolicyContractTest.kt",
        "class": "com.wyattfleming.frameos.navigation.TouchEdgeSwipePolicyContractTest",
        "result": "TEST-com.wyattfleming.frameos.navigation.TouchEdgeSwipePolicyContractTest.xml",
    },
}
sources = {
    "classifier": suites["classifier"]["source"],
    "router": suites["router"]["source"],
    "input": suites["policy"]["source"],
    "activity": frame / "app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt",
}
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    *[argument for suite in suites.values() for argument in ("--tests", suite["class"])],
]
EXPECTED_CASES = 17


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_regular(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")


def source_test_count(path: Path) -> int:
    return path.read_text(encoding="utf-8").count("@Test")


def run(label: str, expected_failed_names: set[str]) -> dict[str, object]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w", encoding="utf-8") as stream:
        completed = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)

    total_cases = total_failures = total_errors = total_skipped = 0
    actual_failed_names: set[str] = set()
    xmls: dict[str, str] = {}
    for name, suite in suites.items():
        candidates = list(report_dir.rglob(str(suite["result"])))
        if len(candidates) != 1:
            raise RuntimeError(f"expected one {name} JUnit result, found {candidates}")
        copied = out / f"{label}-{name}.xml"
        shutil.copy2(candidates[0], copied)
        root_element = ElementTree.parse(copied).getroot()
        cases = list(root_element.iter("testcase"))
        failures = [case for case in cases if case.find("failure") is not None]
        errors = [case for case in cases if case.find("error") is not None]
        skipped = [case for case in cases if case.find("skipped") is not None]
        for case in failures:
            failure = case.find("failure")
            if failure is None or failure.get("type") != "java.lang.AssertionError":
                raise RuntimeError(f"{name} did not fail through an assertion: {case.get('name')}")
            actual_failed_names.add(case.get("name") or "")
        total_cases += len(cases)
        total_failures += len(failures)
        total_errors += len(errors)
        total_skipped += len(skipped)
        xmls[name] = copied.name

    if total_cases != EXPECTED_CASES or total_errors or total_skipped:
        raise RuntimeError(
            f"unexpected JUnit outcome tests={total_cases} failures={total_failures} "
            f"errors={total_errors} skipped={total_skipped}"
        )
    if actual_failed_names != expected_failed_names or total_failures != len(expected_failed_names):
        raise RuntimeError(f"unexpected failed cases {actual_failed_names}; expected {expected_failed_names}")
    if bool(expected_failed_names) == (completed.returncode == 0):
        raise RuntimeError(f"unexpected Gradle exit {completed.returncode} for {label}")
    return {
        "exit": completed.returncode,
        "tests": total_cases,
        "failures": total_failures,
        "errors": total_errors,
        "skipped": total_skipped,
        "failedCases": sorted(actual_failed_names),
        "log": log.name,
        "junitXml": xmls,
    }


def mutate(
    label: str,
    source_name: str,
    before: str,
    after: str,
    expected_failed_names: set[str],
) -> dict[str, object]:
    path = sources[source_name]
    text = originals[source_name].decode("utf-8")
    if text.count(before) != 1 or after in text:
        raise RuntimeError(f"expected one untouched {label} mutation marker")
    try:
        path.write_text(text.replace(before, after, 1), encoding="utf-8")
        return run(label, expected_failed_names)
    finally:
        path.write_bytes(originals[source_name])
        if sha256(path) != source_hashes[source_name]:
            raise RuntimeError(f"exact {source_name} source restoration failed")


for path in [*sources.values(), *[suite["test"] for suite in suites.values()]]:
    require_regular(path)
actual_annotation_count = sum(source_test_count(suite["test"]) for suite in suites.values())
if actual_annotation_count != EXPECTED_CASES:
    raise RuntimeError(f"focused source @Test count changed: {actual_annotation_count}")
originals = {name: path.read_bytes() for name, path in sources.items()}
source_hashes = {name: sha256(path) for name, path in sources.items()}
test_hashes = {str(suite["test"].relative_to(root)): sha256(suite["test"]) for suite in suites.values()}

try:
    initial = run("gesture-initial-green", set())
    slop_red = mutate(
        "gesture-slop-source-red",
        "classifier",
        "if (maxOf(horizontalDistance, verticalDistance) <= directionalTouchSlopPx) return null",
        "if (false) return null",
        {
            "sub slop outward and vertical jitter recovers into a deliberate inward swipe",
            "directional slop boundary drift recovers on both edges",
        },
    )
    width_red = mutate(
        "gesture-immersive-width-source-red",
        "input",
        "const val IMMERSIVE_MEDIA_EDGE_START_DP = 48",
        "const val IMMERSIVE_MEDIA_EDGE_START_DP = 24",
        {"immersive media uses a wider edge target only through a per down callback"},
    )
    restored = run("gesture-restored-green", set())
    if test_hashes != {str(suite["test"].relative_to(root)): sha256(suite["test"]) for suite in suites.values()}:
        raise RuntimeError("focused gesture tests changed during proof")
    receipt = {
        "proof": "frameos-gesture-reliability-v2",
        "passed": True,
        "command": command,
        "sourceTestAnnotationCount": actual_annotation_count,
        "initial": initial,
        "slopRed": slop_red,
        "immersiveWidthRed": width_red,
        "restored": restored,
        "originalSourceSha256": source_hashes,
        "restoredSourceSha256": {name: sha256(path) for name, path in sources.items()},
        "unchangedTests": test_hashes,
        "scope": "legacyArmv7 JVM classifier/router/input/activity source contracts; physical touchscreen behavior remains pending device QA",
    }
    (out / "gesture-source-proof.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
finally:
    for name, path in sources.items():
        path.write_bytes(originals[name])
