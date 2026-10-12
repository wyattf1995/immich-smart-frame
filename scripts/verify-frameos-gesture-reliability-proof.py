#!/usr/bin/env python3
"""Prove the touchscreen jitter and immersive-edge policy on the NAS JVM runner."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1]).resolve()
frame = root / "frameos"
out = root / "reports/frameos-legacy/gesture-reliability-proof"
out.mkdir(parents=True, exist_ok=True)
sources = {
    "classifier": frame / "app/src/main/kotlin/com/wyattfleming/frameos/navigation/TouchEdgeSwipeClassifier.kt",
    "input": frame / "app/src/main/kotlin/com/wyattfleming/frameos/ui/FrameTouchInputLayout.kt",
}
tests = [
    frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/TouchEdgeSwipeClassifierTest.kt",
    frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/TouchModeNavigationRouterTest.kt",
    frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/TouchEdgeSwipePolicyContractTest.kt",
]
original = {name: path.read_bytes() for name, path in sources.items()}
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
test_hashes = {str(path.relative_to(root)): sha(path) for path in tests}
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", ":app:testDebugUnitTest",
    "--tests", "com.wyattfleming.frameos.navigation.TouchEdgeSwipeClassifierTest",
    "--tests", "com.wyattfleming.frameos.navigation.TouchModeNavigationRouterTest",
    "--tests", "com.wyattfleming.frameos.navigation.TouchEdgeSwipePolicyContractTest",
]
report_dir = frame / "app/build/test-results/testDebugUnitTest"


def run(label, expected_failure=None):
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / (label + ".log")
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    suites = [ET.parse(path).getroot() for path in report_dir.glob("TEST-*.xml")]
    failures = [case.attrib.get("name") for suite in suites for case in suite.findall("testcase")
                if case.find("failure") is not None]
    count = sum(int(suite.get("tests", "0")) for suite in suites)
    if expected_failure is None:
        assert result.returncode == 0 and count >= 16 and not failures, (label, result.returncode, failures, log.read_text()[-6000:])
    else:
        assert result.returncode != 0 and expected_failure in failures, (label, result.returncode, failures, log.read_text()[-6000:])
    shutil.copytree(report_dir, out / (label + "-xml"))
    print(label + ": verified", flush=True)
    return {"exit": result.returncode, "tests": count, "failedCases": failures}


def mutate(label, source_name, before, after, expected_failure):
    path = sources[source_name]
    text = original[source_name].decode()
    assert text.count(before) == 1, (label, before)
    path.write_text(text.replace(before, after, 1))
    try:
        result = run(label, expected_failure)
    finally:
        path.write_bytes(original[source_name])
    assert path.read_bytes() == original[source_name]
    return result


try:
    initial = run("gesture-initial")
    slop_red = mutate(
        "gesture-slop-red",
        "classifier",
        "if (maxOf(horizontalDistance, verticalDistance) < directionalTouchSlopPx) return null",
        "if (false) return null",
        "sub slop outward and vertical jitter recovers into a deliberate inward swipe",
    )
    width_red = mutate(
        "gesture-immersive-width-red",
        "input",
        "const val IMMERSIVE_MEDIA_EDGE_START_DP = 48",
        "const val IMMERSIVE_MEDIA_EDGE_START_DP = 24",
        "immersive media uses a wider edge target only through a per down callback",
    )
    green = run("gesture-green")
    assert test_hashes == {str(path.relative_to(root)): sha(path) for path in tests}
    receipt = {
        "proof": "frameos-gesture-reliability-v1",
        "passed": True,
        "initial": initial,
        "slopRed": slop_red,
        "immersiveWidthRed": width_red,
        "green": green,
        "restoredSourceSha256": {name: sha(path) for name, path in sources.items()},
        "unchangedTests": test_hashes,
        "scope": "JVM classifier/router and source contracts; physical touchscreen behavior remains pending device QA",
    }
    (out / "gesture-source-proof.json").write_text(json.dumps(receipt, indent=2) + "\n")
finally:
    for name, path in sources.items():
        path.write_bytes(original[name])
