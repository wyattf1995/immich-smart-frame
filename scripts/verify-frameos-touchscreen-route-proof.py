#!/usr/bin/env python3
"""Prove the reserved HA touchscreen route before restoring the exact source."""
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
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/web/HomeAssistantRoute.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/web/HomeAssistantRouteTest.kt"
result_name = "TEST-com.wyattfleming.frameos.web.HomeAssistantRouteTest.xml"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    "--tests", "com.wyattfleming.frameos.web.HomeAssistantRouteTest",
]

before = '''            if (touchscreenRoute) TOUCHSCREEN_WRAPPER_PATH else WRAPPER_PATH,
            "v=${if (touchscreenRoute) TOUCHSCREEN_WRAPPER_VERSION else version}",
'''
after = '''            WRAPPER_PATH,
            "v=$version",
'''


def copy_and_assert_junit(label: str, expected_failures: int) -> str:
    candidates = list(report_dir.rglob(result_name))
    assert len(candidates) == 1, candidates
    copied = out / f"{label}.xml"
    shutil.copy2(candidates[0], copied)
    root_element = ElementTree.parse(copied).getroot()
    cases = list(root_element.iter("testcase"))
    failures = [failure for case in cases for failure in case.findall("failure")]
    errors = [error for case in cases for error in case.findall("error")]
    assert len(cases) == 7, len(cases)
    assert len(failures) == expected_failures, len(failures)
    assert not errors, errors
    if expected_failures:
        failed_case_names = {case.get("name") for case in cases if case.find("failure") is not None}
        assert failed_case_names == {
            "reserved touchscreen route selects its dedicated wrapper and cache version",
            "reserved touchscreen route accepts its trailing slash",
        }, failed_case_names
        for failure in failures:
            detail = (failure.get("message") or "") + (failure.text or "")
            assert failure.get("type") == "org.junit.ComparisonFailure", detail
            assert "frameos-touchscreen-panel.html" in detail, detail
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
    negative_exit, negative_log, negative_xml = run("touchscreen-route-source-red", expected_failures=2)
    assert negative_exit != 0, negative_log
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

restored_exit, restored_log, restored_xml = run("touchscreen-route-restored-green", expected_failures=0)
assert restored_exit == 0, restored_log
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "touchscreen-route-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "reserved Home Assistant touchscreen route",
    "negative_exit": negative_exit,
    "negative_tests": 7,
    "negative_assertion_failures": 2,
    "restored_green_exit": restored_exit,
    "restored_tests": 7,
    "restored_failures": 0,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": "touchscreen-route-source-red.log",
    "negative_junit_xml": negative_xml,
    "restored_log": "touchscreen-route-restored-green.log",
    "restored_junit_xml": restored_xml,
}, indent=2) + "\n")
print("touchscreen route source-only red / exact restore green verified", flush=True)
