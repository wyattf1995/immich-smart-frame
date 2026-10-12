#!/usr/bin/env python3
"""Prove Art keeps running after idle expiry before exact source restoration."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree

root = Path(sys.argv[1]).resolve()
frame = root / "frameos"
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/navigation/FrameMode.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/FrameReducerTest.kt"
result_name = "TEST-com.wyattfleming.frameos.navigation.FrameReducerTest.xml"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    "--tests", "com.wyattfleming.frameos.navigation.FrameReducerTest",
]

before = 'get() = this == ART'
after = 'get() = false'


def copy_and_assert_junit(label: str, expected_failures: int) -> str:
    candidates = list(report_dir.rglob(result_name))
    assert len(candidates) == 1, candidates
    copied = out / f"{label}.xml"
    shutil.copy2(candidates[0], copied)
    root_element = ElementTree.parse(copied).getroot()
    cases = list(root_element.iter("testcase"))
    failures = [failure for case in cases for failure in case.findall("failure")]
    errors = [error for case in cases for error in case.findall("error")]
    assert len(cases) == 9, len(cases)
    assert len(failures) == expected_failures, len(failures)
    assert not errors, errors
    if expected_failures:
        failed_case_names = {case.get("name") for case in cases if case.find("failure") is not None}
        assert failed_case_names == {"stale idle expiry preserves a selected Art mode"}, failed_case_names
        for failure in failures:
            detail = (failure.get("message") or "") + (failure.text or "")
            assert failure.get("type") == "java.lang.AssertionError", detail
            assert "mode=ART" in detail and "mode=PHOTOS" in detail, detail
    return copied.name


def run(label: str, expected_failures: int) -> tuple[int, str, str]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    return result.returncode, log.read_text(), copy_and_assert_junit(label, expected_failures)


try:
    text = original.decode()
    assert text.count(before) == 1
    source.write_text(text.replace(before, after))
    negative_exit, negative_log, negative_xml = run("art-idle-source-red", expected_failures=1)
    assert negative_exit != 0, negative_log
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

restored_exit, restored_log, restored_xml = run("art-idle-restored-green", expected_failures=0)
assert restored_exit == 0, restored_log
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "art-idle-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "Art remains selected until the user switches modes",
    "negative_exit": negative_exit,
    "negative_tests": 9,
    "negative_assertion_failures": 1,
    "restored_green_exit": restored_exit,
    "restored_tests": 9,
    "restored_failures": 0,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": "art-idle-source-red.log",
    "negative_junit_xml": negative_xml,
    "restored_log": "art-idle-restored-green.log",
    "restored_junit_xml": restored_xml,
}, indent=2) + "\n")
print("Art idle source-only red / exact restore green verified", flush=True)
