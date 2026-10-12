#!/usr/bin/env python3
"""Prove fullscreen media navigation source contracts with exact restore."""
from __future__ import annotations

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
report_dir = frame / "app/build/test-results/testDebugUnitTest"
suites = {
    "media_navigation": {
        "source": frame / "app/src/main/kotlin/com/wyattfleming/frameos/navigation/TouchMediaNavigation.kt",
        "test": frame / "app/src/test/kotlin/com/wyattfleming/frameos/navigation/TouchMediaNavigationTest.kt",
        "class": "com.wyattfleming.frameos.navigation.TouchMediaNavigationTest",
        "result": "TEST-com.wyattfleming.frameos.navigation.TouchMediaNavigationTest.xml",
        "marker": "val contentHasRailInset: Boolean get() = navigationEnabled && !immersiveMedia",
        "replacement": "val contentHasRailInset: Boolean get() = navigationEnabled",
        "failed": {"Photos and Art are fullscreen until their menu is revealed then dismissed"},
    },
    "integration": {
        "source": frame / "app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt",
        "test": frame / "app/src/test/kotlin/com/wyattfleming/frameos/MainActivityFullscreenMediaContractTest.kt",
        "class": "com.wyattfleming.frameos.MainActivityFullscreenMediaContractTest",
        "result": "TEST-com.wyattfleming.frameos.MainActivityFullscreenMediaContractTest.xml",
        "marker": "val inset = if (touchMediaNavigation.contentHasRailInset) dp(88) else 0",
        "replacement": "val inset = dp(88)",
        "failed": {"render computes media navigation before showing web content and updates only layout margins"},
    },
}
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    *[argument for suite in suites.values() for argument in ("--tests", suite["class"])],
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def assert_regular(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")


def run(label: str, expected_failures: int) -> dict[str, object]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"fullscreen-navigation-{label}.log"
    with log.open("w") as stream:
        completed = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)

    suite_results: dict[str, object] = {}
    total_cases = total_failures = 0
    for name, suite in suites.items():
        reports = list(report_dir.rglob(str(suite["result"])))
        if len(reports) != 1:
            raise RuntimeError(f"expected one {name} JUnit result, found {reports}")
        copied = out / f"fullscreen-navigation-{label}-{name}.xml"
        shutil.copy2(reports[0], copied)
        root_element = ElementTree.parse(copied).getroot()
        cases = list(root_element.iter("testcase"))
        failures = [case for case in cases if case.find("failure") is not None]
        errors = [case for case in cases if case.find("error") is not None]
        skipped = [case for case in cases if case.find("skipped") is not None]
        suite_expected = 1 if expected_failures else 0
        if len(cases) != 4 or len(failures) != suite_expected or errors or skipped:
            raise RuntimeError(
                f"unexpected {name} JUnit outcome cases={len(cases)} failures={len(failures)} "
                f"errors={len(errors)} skipped={len(skipped)}"
            )
        failed_names = {case.get("name") for case in failures}
        expected_names = set(suite["failed"]) if suite_expected else set()
        if failed_names != expected_names:
            raise RuntimeError(f"unexpected {name} failed tests: {failed_names}")
        if any(case.find("failure").get("type") != "java.lang.AssertionError" for case in failures):
            raise RuntimeError(f"{name} failed for a reason other than the expected assertion")
        suite_results[name] = {"tests": len(cases), "failures": len(failures), "junit_xml": copied.name}
        total_cases += len(cases)
        total_failures += len(failures)

    if total_cases != 8 or total_failures != expected_failures:
        raise RuntimeError(f"unexpected combined outcome tests={total_cases} failures={total_failures}")
    return {
        "exit": completed.returncode,
        "tests": total_cases,
        "failures": total_failures,
        "log": log.name,
        "suites": suite_results,
    }


for suite in suites.values():
    assert_regular(suite["source"])
    assert_regular(suite["test"])
originals = {name: suite["source"].read_bytes() for name, suite in suites.items()}
source_hashes = {name: sha256(suite["source"]) for name, suite in suites.items()}
test_hashes = {name: sha256(suite["test"]) for name, suite in suites.items()}
negative: dict[str, object] | None = None
try:
    for name, suite in suites.items():
        text = originals[name].decode("utf-8")
        marker = str(suite["marker"])
        if text.count(marker) != 1:
            raise RuntimeError(f"expected exactly one {name} mutation marker")
        suite["source"].write_text(text.replace(marker, str(suite["replacement"]), 1), encoding="utf-8")
    negative = run("source-red", expected_failures=2)
    if negative["exit"] == 0:
        raise RuntimeError("fullscreen navigation mutations unexpectedly passed")
finally:
    for name, suite in suites.items():
        suite["source"].write_bytes(originals[name])
        if sha256(suite["source"]) != source_hashes[name]:
            raise RuntimeError(f"exact {name} source restoration failed")

restored = run("restored-green", expected_failures=0)
if restored["exit"] != 0:
    raise RuntimeError("restored fullscreen navigation tests failed")
for name, suite in suites.items():
    if sha256(suite["test"]) != test_hashes[name]:
        raise RuntimeError(f"{name} test changed during proof")

(out / "fullscreen-navigation-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "disable immersive rail removal and replace dynamic inset with dp(88), then exact restore",
    "command": command,
    "negative_exit": negative["exit"],
    "negative_tests": negative["tests"],
    "negative_assertion_failures": negative["failures"],
    "negative_suites": negative["suites"],
    "restored_green_exit": restored["exit"],
    "restored_tests": restored["tests"],
    "restored_failures": restored["failures"],
    "restored_suites": restored["suites"],
    "restored_source_sha256": {name: sha256(suite["source"]) for name, suite in suites.items()},
    "unchanged_test_sha256": test_hashes,
    "negative_log": negative["log"],
    "restored_log": restored["log"],
}, indent=2) + "\n", encoding="utf-8")
print("fullscreen navigation source-only red / exact restore green verified", flush=True)
