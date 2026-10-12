#!/usr/bin/env python3
"""Run focused legacy TLS JUnit source mutations and exact restoration."""
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
out = root / "reports" / "frameos-legacy"
out.mkdir(parents=True, exist_ok=True)
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/weather/LegacyIsrgRootTls.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/weather/LegacyIsrgRootTlsTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.frameos.weather.LegacyIsrgRootTlsTest.xml"
test_class = "com.wyattfleming.frameos.weather.LegacyIsrgRootTlsTest"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest", "--tests", test_class,
]

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def run(label: str, failures: int, names: set[str]) -> dict:
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
    assert len(cases) == 8, len(cases)
    assert len(failed) == failures, failed
    assert failed == names, failed
    skipped = [item for case in cases for item in case.findall("skipped")]
    assert not errors and not skipped, (errors, skipped)
    for case in cases:
        failure = case.find("failure")
        if failure is not None:
            assert failure.get("type") in {"java.lang.AssertionError", "org.junit.ComparisonFailure"}, failure.attrib
    return {"exit": result.returncode, "log": log.name, "junit_xml": xml.name,
            "tests": len(cases), "failures": len(failed), "errors": 0, "skipped": 0,
            "failed_cases": sorted(failed)}

original = source.read_bytes()
source_hash = digest(source)
test_hash = digest(test)
proofs = []

def mutation(label: str, before: str, after: str, failed_names: set[str]) -> None:
    text = original.decode()
    assert text.count(before) == 1 and after not in text
    try:
        source.write_text(text.replace(before, after), encoding="utf-8")
        result = run(label + "-source-red", len(failed_names), failed_names)
        assert result["exit"] != 0
        proofs.append({"label": label, "negative_assertion_failures": len(failed_names), "negative_tests": 8, **result})
    finally:
        source.write_bytes(original)
        assert digest(source) == source_hash

mutations = (
    (
        "legacy-tls-api23-gate",
        "sdkInt == Build.VERSION_CODES.M",
        # Preserve the HTTPS smart cast while rejecting the tested SDK.
        "sdkInt == -1",
        {"legacy socket factory is applied only to app scoped pre26 HTTPS connections"},
    ),
    (
        "legacy-tls-supplemental-fallback",
        "supplemental.checkServerTrusted(chain, authType)",
        # Omitting only the supplemental validator must fail both the call
        # requirement and the rejected-chain guard through JUnit assertions.
        # Repeating the platform call instead throws a certificate exception
        # and is not an assertion proof of these two requirements.
        "Unit /* source-only negative: omit supplemental validation */",
        {
            "supplemental root is used only after platform rejects the chain",
            "a chain rejected by both trust managers stays rejected",
        },
    ),
)
# Validate every marker before paying for a Gradle invocation. Replacement text
# must be absent, but a different existing platform delegation is legitimate.
for label, before, after, names in mutations:
    assert original.decode().count(before) == 1 and after not in original.decode(), label
for args in mutations:
    mutation(*args)
green = run("legacy-tls-restored-green", 0, set())
assert green["exit"] == 0
assert digest(source) == source_hash
assert digest(test) == test_hash
(out / "legacy-tls-source-proof.json").write_text(json.dumps({
    "passed": True,
    "source_sha256": source_hash,
    "unchanged_test_sha256": test_hash,
    "negative_proofs": proofs,
    "restored_green": {"tests": 8, "failures": 0, **green},
}, indent=2) + "\n")
print("legacy TLS source-only red / exact restore green verified")
