#!/usr/bin/env python3
"""Prove the API23 ISRG PEM has parser-safe boundaries and restore exactly."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

if not __debug__:
    raise RuntimeError("optimized Python is not permitted")
if len(sys.argv) != 2:
    raise SystemExit(f"usage: {Path(sys.argv[0]).name} <repository-root>")

root = Path(sys.argv[1]).resolve()
frame = root / "frameos"
out = root / "reports" / "frameos-api23-pem"
out.mkdir(parents=True, exist_ok=True)
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/weather/LegacyIsrgRootTls.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/weather/LegacyIsrgRootTlsTest.kt"
report_dir = frame / "frame-shared/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.frameos.weather.LegacyIsrgRootTlsTest.xml"
test_class = "com.wyattfleming.frameos.weather.LegacyIsrgRootTlsTest"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":frame-shared:testDebugUnitTest", "--tests", test_class,
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
    assert len(cases) == 9, len(cases)
    assert failed == expected_failed_cases, failed
    assert len(failed) == expected_failures, failed
    assert not errors and not skipped, (errors, skipped)
    return {
        "exit": result.returncode,
        "log": log.name,
        "junit_xml": xml.name,
        "tests": len(cases),
        "failures": len(failed),
        "errors": 0,
        "skipped": 0,
        "failed_cases": sorted(failed),
    }


original = source.read_bytes()
source_hash = digest(source)
test_hash = digest(test)
needle = 'private const val ISRG_ROOT_X1_PEM = """-----BEGIN CERTIFICATE-----'
replacement = 'private const val ISRG_ROOT_X1_PEM = """\\n-----BEGIN CERTIFICATE-----'
assert original.decode().count(needle) == 1
assert replacement not in original.decode()
try:
    source.write_text(original.decode().replace(needle, replacement), encoding="utf-8")
    red = run(
        "api23-pem-leading-newline-source-red",
        1,
        {"embedded PEM has no parser-hostile leading or trailing whitespace"},
    )
    assert red["exit"] != 0
finally:
    source.write_bytes(original)
    assert digest(source) == source_hash

green = run("api23-pem-restored-green", 0, set())
assert green["exit"] == 0
assert digest(source) == source_hash
assert digest(test) == test_hash
(out / "api23-pem-source-proof.json").write_text(json.dumps({
    "passed": True,
    "target": "legacyArmv7",
    "source_sha256": source_hash,
    "unchanged_test_sha256": test_hash,
    "negative": {"negative_assertion_failures": 1, **red},
    "restored_green": green,
}, indent=2) + "\n")
print("API23 PEM source-only red / exact restore green verified")
