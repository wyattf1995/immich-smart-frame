#!/usr/bin/env python3
"""Negative proof for the dashboard browser-suspension integration contract."""
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
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/MainActivityTouchDashboardContractTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest", "--tests", "com.wyattfleming.frameos.MainActivityTouchDashboardContractTest",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def method_body(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing signature: {signature}")
    open_brace = text.find("{", start)
    depth = 0
    for index in range(open_brace, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return start, index + 1
    raise RuntimeError(f"unterminated method: {signature}")


def run(label: str, expected_failures: int, expected_names: set[str]) -> dict[str, object]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        completed = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    results = list(report_dir.rglob("TEST-com.wyattfleming.frameos.MainActivityTouchDashboardContractTest.xml"))
    if len(results) != 1:
        raise RuntimeError(f"expected one JUnit result, found {results}")
    copied = out / f"{label}.xml"
    shutil.copy2(results[0], copied)
    root_element = ElementTree.parse(copied).getroot()
    cases = list(root_element.iter("testcase"))
    failures = [case for case in cases if case.find("failure") is not None]
    errors = [case for case in cases if case.find("error") is not None]
    if len(cases) != 4 or len(failures) != expected_failures or errors:
        raise RuntimeError(f"unexpected JUnit outcome cases={len(cases)} failures={len(failures)} errors={len(errors)}")
    names = {case.get("name") for case in failures}
    if names != expected_names:
        raise RuntimeError(f"unexpected failed tests: {names}")
    return {
        "exit": completed.returncode,
        "tests": len(cases),
        "failures": len(failures),
        "log": log.name,
        "junit_xml": copied.name,
    }


if not source.is_file() or source.is_symlink() or not test.is_file() or test.is_symlink():
    raise RuntimeError("required regular source or contract test missing")
original = source.read_bytes()
source_hash = sha256(source)
test_hash = sha256(test)
try:
    text = original.decode("utf-8")
    begin, end = method_body(text, "private fun render(next: FrameState, announce: Boolean)")
    render = text[begin:end]
    marker = "            webSurface?.suspendAllContent()\n"
    if render.count(marker) != 1:
        raise RuntimeError("expected exactly one dashboard render browser suspension")
    mutated = text[:begin] + render.replace(marker, "", 1) + text[end:]
    source.write_text(mutated, encoding="utf-8")
    negative = run(
        "touch-dashboard-browser-suspend-source-red",
        expected_failures=1,
        expected_names={"dashboard render hides and suspends browser content"},
    )
    if negative["exit"] == 0:
        raise RuntimeError("negative proof unexpectedly passed")
finally:
    source.write_bytes(original)
    if sha256(source) != source_hash:
        raise RuntimeError("MainActivity exact restoration failed")

restored = run(
    "touch-dashboard-browser-suspend-restored-green",
    expected_failures=0,
    expected_names=set(),
)
if restored["exit"] != 0:
    raise RuntimeError("restored contract test failed")
if sha256(test) != test_hash:
    raise RuntimeError("contract test changed during proof")

photos_start_marker = "dashboardVisible = touchDashboardEnabled && !touchscreenSettings.startWithPhotos"
try:
    text = original.decode("utf-8")
    if text.count(photos_start_marker) != 2:
        raise RuntimeError("expected startup and refresh Photos-first expressions")
    source.write_text(text.replace(photos_start_marker, "dashboardVisible = touchDashboardEnabled"), encoding="utf-8")
    photos_start_negative = run(
        "touch-dashboard-photos-start-source-red",
        expected_failures=1,
        expected_names={"startup reads the local touchscreen opt in and physical touch is the dashboard front door"},
    )
    if photos_start_negative["exit"] == 0:
        raise RuntimeError("Photos-start negative proof unexpectedly passed")
finally:
    source.write_bytes(original)
    if sha256(source) != source_hash:
        raise RuntimeError("MainActivity exact restoration failed after Photos-start proof")

photos_start_restored = run(
    "touch-dashboard-photos-start-restored-green",
    expected_failures=0,
    expected_names=set(),
)
if photos_start_restored["exit"] != 0:
    raise RuntimeError("Photos-start restored contract test failed")
if sha256(test) != test_hash:
    raise RuntimeError("contract test changed during Photos-start proof")

(out / "touch-dashboard-integration-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "remove only dashboard render webSurface suspension",
    "original_source_sha256": source_hash,
    "restored_source_sha256": sha256(source),
    "unchanged_test_sha256": test_hash,
    "negative": negative,
    "restored": restored,
    "photos_start_negative": photos_start_negative,
    "photos_start_restored": photos_start_restored,
}, indent=2) + "\n", encoding="utf-8")
print("dashboard integration red / exact restore green verified", flush=True)
