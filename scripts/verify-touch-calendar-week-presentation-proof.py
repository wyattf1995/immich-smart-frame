#!/usr/bin/env python3
"""Run the bounded Week calendar grouping source mutation and exact restoration proof."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

if not __debug__:
    raise RuntimeError("optimized Python is not permitted")

parser = argparse.ArgumentParser()
parser.add_argument("repository_root")
parser.add_argument("--target", choices=("legacyArmv7", "modern"), default="legacyArmv7")
args = parser.parse_args()
root = Path(args.repository_root).resolve()
frame = root / "frameos"
out = root / "reports" / "touch-calendar-week"
out.mkdir(parents=True, exist_ok=True)
source = frame / "touchframe/src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarWeekPresentation.kt"
test = frame / "touchframe/src/test/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarWeekPresentationTest.kt"
report_dir = frame / "touchframe/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.touchframe.calendar.TouchCalendarWeekPresentationTest.xml"
test_class = "com.wyattfleming.touchframe.calendar.TouchCalendarWeekPresentationTest"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", f"-PframeosTarget={args.target}",
    ":touchframe:testDebugUnitTest", "--tests", test_class,
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label: str, expected_failures: int, expected_failed_cases: set[str]) -> dict:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    reports = list(report_dir.rglob(result_name))
    assert len(reports) == 1, reports
    xml = out / f"{label}.xml"
    shutil.copy2(reports[0], xml)
    cases = list(ET.parse(xml).getroot().iter("testcase"))
    failed = {case.get("name") for case in cases if case.find("failure") is not None}
    errors = [error for case in cases for error in case.findall("error")]
    skipped = [item for case in cases for item in case.findall("skipped")]
    assert len(cases) == 4, len(cases)
    assert failed == expected_failed_cases, failed
    assert len(failed) == expected_failures, failed
    assert not errors and not skipped, (errors, skipped)
    for case in cases:
        failure = case.find("failure")
        if failure is not None:
            assert failure.get("type") in {
                "java.lang.AssertionError", "org.junit.ComparisonFailure",
            }, failure.attrib
    return {
        "exit": result.returncode,
        "log": log.name,
        "log_sha256": digest(log),
        "junit_xml": xml.name,
        "junit_xml_sha256": digest(xml),
        "tests": len(cases),
        "failures": len(failed),
        "errors": 0,
        "skipped": 0,
        "failed_cases": sorted(failed),
    }


original = source.read_bytes()
source_hash = digest(source)
test_hash = digest(test)
needle = "const val PREVIEW_PER_DAY = 3"
replacement = "const val PREVIEW_PER_DAY = 4"
source_text = original.decode()
assert source_text.count(needle) == 1 and replacement not in source_text
try:
    source.write_text(source_text.replace(needle, replacement), encoding="utf-8")
    red = run(
        "week-calendar-overflow-source-red",
        1,
        {"groupsSevenDaysWithAllDayFirstAndBoundedPreview"},
    )
    assert red["exit"] != 0
finally:
    source.write_bytes(original)
    assert digest(source) == source_hash

green = run("week-calendar-restored-green", 0, set())
assert green["exit"] == 0
assert digest(source) == source_hash
assert digest(test) == test_hash
(out / "week-calendar-presentation-source-proof.json").write_text(json.dumps({
    "passed": True,
    "target": args.target,
    "source_sha256": source_hash,
    "unchanged_test_sha256": test_hash,
    "negative": {"negative_assertion_failures": 1, **red},
    "restored_green": green,
}, indent=2) + "\n")
print("Week calendar grouping source-only red / exact restore green verified")
