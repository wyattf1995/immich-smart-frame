#!/usr/bin/env python3
"""Prove Home's longer page-load budget with a reversible source-only mutation."""
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
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebPageLoadBudget.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebPageLoadBudgetTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.frameos.web.FrameWebPageLoadBudgetTest.xml"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest", "--tests", "com.wyattfleming.frameos.web.FrameWebPageLoadBudgetTest",
]
original = source.read_bytes()
test_hash = hashlib.sha256(test.read_bytes()).hexdigest()
before = "const val HOME_ASSISTANT_MILLIS = 60_000L"
after = "const val HOME_ASSISTANT_MILLIS = 20_000L"


def run(label: str, expected_failures: int) -> tuple[int, str, str]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    candidates = list(report_dir.rglob(result_name))
    assert len(candidates) == 1, candidates
    copied = out / f"{label}.xml"
    shutil.copy2(candidates[0], copied)
    suite = ElementTree.parse(copied).getroot()
    cases = list(suite.iter("testcase"))
    failures = [case for case in cases if case.find("failure") is not None]
    errors = [case for case in cases if case.find("error") is not None]
    assert len(cases) == 3, len(cases)
    assert len(failures) == expected_failures, len(failures)
    assert not errors, errors
    if expected_failures:
        assert {case.get("name") for case in failures} == {
            "Home Assistant receives the bounded sixty second budget",
        }
    return result.returncode, log.read_text(), copied.name


try:
    text = original.decode()
    assert text.count(before) == 1
    source.write_text(text.replace(before, after))
    negative_exit, negative_log, negative_xml = run("page-load-budget-source-red", expected_failures=1)
    assert negative_exit != 0, negative_log
finally:
    source.write_bytes(original)
    assert source.read_bytes() == original

restored_exit, restored_log, restored_xml = run("page-load-budget-restored-green", expected_failures=0)
assert restored_exit == 0, restored_log
assert hashlib.sha256(test.read_bytes()).hexdigest() == test_hash
(out / "page-load-budget-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "Home Assistant has a 60 second page-load budget while other surfaces retain 20 seconds",
    "negative_exit": negative_exit,
    "negative_tests": 3,
    "negative_assertion_failures": 1,
    "restored_green_exit": restored_exit,
    "restored_tests": 3,
    "restored_failures": 0,
    "restored_source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hash,
    "negative_log": "page-load-budget-source-red.log",
    "negative_junit_xml": negative_xml,
    "restored_log": "page-load-budget-restored-green.log",
    "restored_junit_xml": restored_xml,
}, indent=2) + "\n")
print("Home page-load budget source-only red / exact restore green verified", flush=True)
