#!/usr/bin/env python3
"""NAS-only source-negative proof for the explicit Android Settings return overlay."""
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
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsReturnController.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsReturnControllerTest.kt"
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
report = frame / "app/build/test-results/testDebugUnitTest"
xml_name = "TEST-com.wyattfleming.frameos.ui.FrameExternalSettingsReturnControllerTest.xml"
command = ["./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7", ":app:testDebugUnitTest", "--tests", "com.wyattfleming.frameos.ui.FrameExternalSettingsReturnControllerTest"]
for path in (source, test):
    if not path.is_file() or path.is_symlink(): raise RuntimeError(f"missing regular file: {path}")
original = source.read_bytes(); source_hash = hashlib.sha256(original).hexdigest(); test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
before = "if (!adapter.canDrawOverlays()) return Outcome.OVERLAY_PERMISSION_DENIED"
after = "if (!adapter.canDrawOverlays()) return Outcome.LAUNCHED"

def run(label, failures):
    if report.exists(): shutil.rmtree(report)
    log = out / f"{label}.log"
    with log.open("w") as stream: result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    matches = list(report.rglob(xml_name)); assert len(matches) == 1, matches
    xml = out / f"{label}.xml"; shutil.copy2(matches[0], xml)
    root_xml = ElementTree.parse(xml).getroot(); cases = list(root_xml.iter("testcase"))
    failed = [case for case in cases if case.find("failure") is not None]
    assert len(cases) == 4 and len(failed) == failures and not list(root_xml.iter("error")), (len(cases), len(failed))
    return result.returncode, log.name, xml.name

try:
    text = original.decode(); assert text.count(before) == 1 and after not in text
    source.write_text(text.replace(before, after), encoding="utf-8")
    negative_exit, negative_log, negative_xml = run("settings-return-overlay-red", 1)
    assert negative_exit != 0
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original
restored_exit, restored_log, restored_xml = run("settings-return-overlay-green", 0)
assert restored_exit == 0 and hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "settings-return-overlay-source-proof.json").write_text(json.dumps({
    "passed": True, "negative_exit": negative_exit, "negative_tests": 4, "negative_assertion_failures": 1,
    "restored_green_exit": restored_exit, "restored_tests": 4, "restored_failures": 0,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "unchanged_test_sha256": test_hash,
    "negative_log": negative_log, "negative_junit_xml": negative_xml, "restored_log": restored_log, "restored_junit_xml": restored_xml,
}, indent=2) + "\n", encoding="utf-8")
print("Settings return overlay red / exact restore green verified")
