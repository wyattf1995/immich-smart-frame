#!/usr/bin/env python3
"""Prove touchscreen Home unification with source-only negative checks and exact restore."""
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
rail_source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/ui/TouchNavigationRail.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/MainActivityTouchDashboardContractTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.frameos.MainActivityTouchDashboardContractTest.xml"
command = ["./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7", ":app:testDebugUnitTest", "--tests", "com.wyattfleming.frameos.MainActivityTouchDashboardContractTest"]

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def run(label: str, expected_failures: int, expected_names: set[str]) -> dict[str, object]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        completed = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    reports = list(report_dir.rglob(result_name))
    if len(reports) != 1:
        raise RuntimeError(f"expected one JUnit result, found {reports}")
    copied = out / f"{label}.xml"
    shutil.copy2(reports[0], copied)
    suite = ElementTree.parse(copied).getroot()
    cases = list(suite.iter("testcase"))
    failures = [case for case in cases if case.find("failure") is not None]
    errors = [case for case in cases if case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    names = {case.get("name") for case in failures}
    if len(cases) != 4 or len(failures) != expected_failures or errors or skipped or names != expected_names:
        raise RuntimeError(f"unexpected JUnit outcome cases={len(cases)} failures={len(failures)} errors={len(errors)} skipped={len(skipped)} names={names}")
    if any(case.find("failure").get("type") != "java.lang.AssertionError" for case in failures):
        raise RuntimeError("negative check failed for a reason other than its assertion")
    return {"exit": completed.returncode, "tests": len(cases), "failures": len(failures), "log": log.name, "junit_xml": copied.name}

if any(not path.is_file() or path.is_symlink() for path in (source, rail_source, test)):
    raise RuntimeError("required regular source or contract test missing")
original = source.read_bytes()
rail_original = rail_source.read_bytes()
source_hash = sha256(source)
rail_source_hash = sha256(rail_source)
test_hash = sha256(test)
checks = [
    ("touch-home-startup-source-red", "if (touchNavigationEnabled && !touchscreenSettings.startWithPhotos) {", "if (touchNavigationEnabled) {", "touch startup opens authenticated Home unless Photos startup is configured"),
    ("touch-home-rail-source-red", "it.setModes(TouchNavigationRailPolicy.ordered(availableModes()))", "it.setModes(availableModes())", "rail presents accessible Home and Android Settings return without a native Today destination"),
    ("touch-home-back-lifecycle-source-red", "unregisterHomeBack?.invoke()\n        unregisterHomeBack = if", "unregisterHomeBack = if", "Back and Home controls route to the existing Home mode"),
]
results: dict[str, object] = {}
for label, before, after, failed_name in checks:
    try:
        text = original.decode("utf-8")
        if text.count(before) != 1:
            raise RuntimeError(f"expected one untouched marker for {label}")
        source.write_text(text.replace(before, after, 1), encoding="utf-8")
        negative = run(label, expected_failures=1, expected_names={failed_name})
        if negative["exit"] == 0:
            raise RuntimeError(f"negative proof unexpectedly passed: {label}")
        results[label] = negative
    finally:
        source.write_bytes(original)
        if sha256(source) != source_hash:
            raise RuntimeError(f"MainActivity exact restoration failed: {label}")
    restored = run(label.replace("source-red", "restored-green"), expected_failures=0, expected_names=set())
    if restored["exit"] != 0:
        raise RuntimeError(f"restored contract test failed: {label}")
    results[label.replace("source-red", "restored-green")] = restored

label = "touch-home-visible-rail-source-red"
visible_rail_before = "modes.forEach { mode -> items.addView(item(mode, mode.label, selected == mode) { onOpenMode(mode) }) }"
visible_rail_after = "modes.forEach { mode -> items.addView(item(mode, \"Today\", selected == mode) { }) }"
try:
    rail_text = rail_original.decode("utf-8")
    if rail_text.count(visible_rail_before) != 1:
        raise RuntimeError("expected one untouched Home-visible rail marker")
    rail_source.write_text(rail_text.replace(visible_rail_before, visible_rail_after, 1), encoding="utf-8")
    negative = run(
        label,
        expected_failures=1,
        expected_names={"rail presents accessible Home and Android Settings return without a native Today destination"},
    )
    if negative["exit"] == 0:
        raise RuntimeError(f"negative proof unexpectedly passed: {label}")
    results[label] = negative
finally:
    rail_source.write_bytes(rail_original)
    if sha256(rail_source) != rail_source_hash:
        raise RuntimeError("TouchNavigationRail exact restoration failed")
restored = run(label.replace("source-red", "restored-green"), expected_failures=0, expected_names=set())
if restored["exit"] != 0:
    raise RuntimeError(f"restored contract test failed: {label}")
results[label.replace("source-red", "restored-green")] = restored

if sha256(test) != test_hash:
    raise RuntimeError("contract test changed during proof")
(out / "touch-dashboard-integration-proof.json").write_text(json.dumps({"passed": True, "recipe": "touchscreen startup, accessible Home rail, Android Settings return, and lifecycle-managed Back use the existing authenticated Home mode", "original_source_sha256": source_hash, "restored_source_sha256": sha256(source), "original_rail_source_sha256": rail_source_hash, "restored_rail_source_sha256": sha256(rail_source), "unchanged_test_sha256": test_hash, "checks": results}, indent=2) + "\n", encoding="utf-8")
print("touch Home integration red / exact restore green verified", flush=True)
