#!/usr/bin/env python3
"""Prove Home's page-load budget and its FrameWebSurface hook with reversible mutations."""
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
policy_source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebPageLoadBudget.kt"
surface_source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt"
policy_test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebPageLoadBudgetTest.kt"
contract_test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebPageLoadBudgetContractTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"


def run(label: str, test_class: str, expected_tests: int, expected_failures: int) -> dict:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    command = [
        "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
        ":app:testDebugUnitTest", "--tests", test_class,
    ]
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    result_name = f"TEST-{test_class}.xml"
    candidates = list(report_dir.rglob(result_name))
    assert len(candidates) == 1, candidates
    copied = out / f"{label}.xml"
    shutil.copy2(candidates[0], copied)
    suite = ElementTree.parse(copied).getroot()
    cases = list(suite.iter("testcase"))
    failures = [case for case in cases if case.find("failure") is not None]
    errors = [case for case in cases if case.find("error") is not None]
    assert len(cases) == expected_tests, len(cases)
    assert len(failures) == expected_failures, len(failures)
    assert not errors, errors
    return {
        "exit": result.returncode,
        "tests": expected_tests,
        "failures": expected_failures,
        "log": log.name,
        "junit_xml": copied.name,
        "failure_names": {case.get("name") for case in failures},
    }


policy_original = policy_source.read_bytes()
surface_original = surface_source.read_bytes()
test_hashes = {
    str(policy_test.relative_to(frame)): hashlib.sha256(policy_test.read_bytes()).hexdigest(),
    str(contract_test.relative_to(frame)): hashlib.sha256(contract_test.read_bytes()).hexdigest(),
}

policy_before = "const val HOME_ASSISTANT_MILLIS = 60_000L"
policy_after = "const val HOME_ASSISTANT_MILLIS = 20_000L"
try:
    text = policy_original.decode()
    assert text.count(policy_before) == 1
    policy_source.write_text(text.replace(policy_before, policy_after))
    policy_red = run(
        "page-load-budget-source-red",
        "com.wyattfleming.frameos.web.FrameWebPageLoadBudgetTest",
        expected_tests=3,
        expected_failures=1,
    )
    assert policy_red["exit"] != 0
    assert policy_red["failure_names"] == {"Home Assistant receives the bounded sixty second budget"}
finally:
    policy_source.write_bytes(policy_original)
    assert policy_source.read_bytes() == policy_original

surface_before = "postDelayed(watchdog, pageLoadBudget.millisFor(label, pageLoadWatchdogMillis))"
surface_after = "postDelayed(watchdog, pageLoadWatchdogMillis ?: 20_000L)"
try:
    text = surface_original.decode()
    assert text.count(surface_before) == 1
    surface_source.write_text(text.replace(surface_before, surface_after))
    contract_red = run(
        "page-load-budget-hook-source-red",
        "com.wyattfleming.frameos.web.FrameWebPageLoadBudgetContractTest",
        expected_tests=1,
        expected_failures=1,
    )
    assert contract_red["exit"] != 0
    assert contract_red["failure_names"] == {"surface uses the per-label budget and preserves explicit overrides"}
finally:
    surface_source.write_bytes(surface_original)
    assert surface_source.read_bytes() == surface_original

policy_green = run(
    "page-load-budget-restored-green",
    "com.wyattfleming.frameos.web.FrameWebPageLoadBudgetTest",
    expected_tests=3,
    expected_failures=0,
)
contract_green = run(
    "page-load-budget-hook-restored-green",
    "com.wyattfleming.frameos.web.FrameWebPageLoadBudgetContractTest",
    expected_tests=1,
    expected_failures=0,
)
assert policy_green["exit"] == 0
assert contract_green["exit"] == 0
assert policy_source.read_bytes() == policy_original
assert surface_source.read_bytes() == surface_original
for relative, expected_hash in test_hashes.items():
    assert hashlib.sha256((frame / relative).read_bytes()).hexdigest() == expected_hash

for result in (policy_red, contract_red, policy_green, contract_green):
    result["failure_names"] = sorted(result["failure_names"])
(out / "page-load-budget-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "Home has a 60 second page-load budget; the surface uses it unless an explicit override is supplied",
    "policy_source_red": policy_red,
    "surface_hook_source_red": contract_red,
    "policy_restored_green": policy_green,
    "surface_hook_restored_green": contract_green,
    "restored_policy_source_sha256": hashlib.sha256(policy_source.read_bytes()).hexdigest(),
    "restored_surface_source_sha256": hashlib.sha256(surface_source.read_bytes()).hexdigest(),
    "unchanged_test_sha256": test_hashes,
}, indent=2) + "\n")
print("Home page-load budget and surface hook source-only red / exact restore green verified", flush=True)
