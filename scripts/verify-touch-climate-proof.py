#!/usr/bin/env python3
"""Prove legacy Home Assistant climate fallbacks and target command rejection."""
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
out = root / "reports" / "touch-climate"
out.mkdir(parents=True, exist_ok=True)
source_files = {
    "parser": frame / "touchframe/src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeParser.kt",
    "repository": frame / "touchframe/src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepository.kt",
}
test_files = {
    "parser": frame / "touchframe/src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeParserTest.kt",
    "repository": frame / "touchframe/src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepositoryTest.kt",
}
report_dir = frame / "touchframe/build/test-results/testDebugUnitTest"
test_classes = (
    "com.wyattfleming.touchframe.home.TouchHomeParserTest",
    "com.wyattfleming.touchframe.home.TouchHomeRepositoryTest",
)
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", f"-PframeosTarget={args.target}",
    ":touchframe:testDebugUnitTest",
]
for test_class in test_classes:
    command.extend(("--tests", test_class))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def collect_junit(label: str, expected_failed_cases: set[str], expected_exit: int) -> dict:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    reports = []
    for test_class in test_classes:
        paths = list(report_dir.rglob(f"TEST-{test_class}.xml"))
        assert len(paths) == 1, (test_class, paths)
        report = paths[0]
        retained = out / f"{label}-{report.name}"
        shutil.copy2(report, retained)
        reports.append(retained)
    cases = []
    for report in reports:
        cases.extend(ET.parse(report).getroot().iter("testcase"))
    failed = {case.get("name") for case in cases if case.find("failure") is not None}
    errors = [error for case in cases for error in case.findall("error")]
    skipped = [skip for case in cases for skip in case.findall("skipped")]
    assert len(cases) == 19, len(cases)
    assert failed == expected_failed_cases, failed
    assert not errors and not skipped, (errors, skipped)
    for case in cases:
        failure = case.find("failure")
        if failure is not None:
            assert failure.get("type") in {
                "java.lang.AssertionError", "org.junit.ComparisonFailure",
            }, failure.attrib
    if expected_exit == 0:
        assert result.returncode == 0, result.returncode
    else:
        assert result.returncode != 0, result.returncode
    return {
        "exit": result.returncode,
        "log": log.name,
        "log_sha256": digest(log),
        "junit_xml": [report.name for report in reports],
        "junit_xml_sha256": {report.name: digest(report) for report in reports},
        "tests": len(cases),
        "failures": len(failed),
        "errors": 0,
        "skipped": 0,
        "failed_cases": sorted(failed),
    }


original = {name: path.read_bytes() for name, path in source_files.items()}
source_hashes = {name: digest(path) for name, path in source_files.items()}
test_hashes = {name: digest(path) for name, path in test_files.items()}


def prove_mutation(
    source_name: str,
    needle: str,
    replacement: str,
    label: str,
    expected_failed_cases: set[str],
) -> dict:
    path = source_files[source_name]
    text = original[source_name].decode("utf-8")
    assert text.count(needle) == 1, (source_name, needle, text.count(needle))
    assert replacement not in text, (source_name, replacement)
    try:
        path.write_text(text.replace(needle, replacement), encoding="utf-8")
        red = collect_junit(label, expected_failed_cases, expected_exit=1)
        return {"negative_assertion_failures": len(expected_failed_cases), **red}
    finally:
        path.write_bytes(original[source_name])
        assert digest(path) == source_hashes[source_name]


config_fallback_red = prove_mutation(
    "parser",
    "recognizedTemperatureUnit(fallbackTemperatureUnit)",
    "recognizedTemperatureUnit(\"°C\")",
    "climate-config-unit-fallback-source-red",
    {
        "climate parser uses only a recognized config unit when old HA omits the entity unit",
        "old HA climate unit falls back to config once and retains target guard",
        "observed off thermostat stays connected and cannot post a made up target",
    },
)
target_null_red = prove_mutation(
    "repository",
    "if (!value.isFinite() || climate.entityId != configuration.climateEntityId || !climate.canSetTarget) return false",
    "if (!value.isFinite() || climate.entityId != configuration.climateEntityId) return false",
    "climate-reject-null-target-source-red",
    {
        "range-only climate never posts a single target command",
        "observed off thermostat stays connected and cannot post a made up target",
    },
)
green = collect_junit("climate-restored-green", set(), expected_exit=0)
assert all(digest(path) == source_hashes[name] for name, path in source_files.items())
assert all(digest(path) == test_hashes[name] for name, path in test_files.items())
(out / "touch-climate-source-proof.json").write_text(json.dumps({
    "passed": True,
    "target": args.target,
    "source_sha256": source_hashes,
    "unchanged_test_sha256": test_hashes,
    "negative_mutations": [config_fallback_red, target_null_red],
    "restored_green": green,
}, indent=2) + "\n")
print("Touch climate fallback/target source-only red / exact restore green verified")
