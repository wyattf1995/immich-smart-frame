#!/usr/bin/env python3
"""Prove an HTTPS OAuth callback is intercepted before Gecko can load it."""
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
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/auth/FrameOAuthNavigationPolicy.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/auth/HttpsOAuthRedirectPolicyTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.frameos.auth.HttpsOAuthRedirectPolicyTest.xml"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest", "--tests", "com.wyattfleming.frameos.auth.HttpsOAuthRedirectPolicyTest",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label: str, expected_failures: int) -> dict[str, object]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        completed = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    reports = list(report_dir.rglob(result_name))
    if len(reports) != 1:
        raise RuntimeError(f"expected one JUnit result, found {reports}")
    copied = out / f"{label}.xml"
    shutil.copy2(reports[0], copied)
    root_element = ElementTree.parse(copied).getroot()
    cases = list(root_element.iter("testcase"))
    failures = [case for case in cases if case.find("failure") is not None]
    errors = [case for case in cases if case.find("error") is not None]
    if len(cases) != 4 or len(failures) != expected_failures or errors:
        raise RuntimeError(f"unexpected JUnit outcome cases={len(cases)} failures={len(failures)} errors={len(errors)}")
    names = {case.get("name") for case in failures}
    expected = {
        "routesExactHttpsCallbackBeforeSameOriginNavigation",
        "treatsEquivalentDefaultHttpsPortAsTheExactCallback",
    } if expected_failures else set()
    if names != expected:
        raise RuntimeError(f"unexpected failed tests: {names}")
    return {"exit": completed.returncode, "tests": len(cases), "failures": len(failures), "log": log.name, "junit_xml": copied.name}

if not source.is_file() or source.is_symlink() or not test.is_file() or test.is_symlink():
    raise RuntimeError("required regular source or test missing")
original = source.read_bytes()
source_hash = sha256(source)
test_hash = sha256(test)
marker = "if (callbackTarget.isExactHttpsCallback(requestUrl)) return Decision.CALLBACK"
try:
    text = original.decode("utf-8")
    if text.count(marker) != 1:
        raise RuntimeError("expected exactly one HTTPS callback interception marker")
    source.write_text(text.replace(marker, "if (false) return Decision.CALLBACK", 1), encoding="utf-8")
    negative = run("oauth-https-redirect-source-red", expected_failures=2)
    if negative["exit"] == 0:
        raise RuntimeError("negative proof unexpectedly passed")
finally:
    source.write_bytes(original)
    if sha256(source) != source_hash:
        raise RuntimeError("exact source restoration failed")

restored = run("oauth-https-redirect-restored-green", expected_failures=0)
if restored["exit"] != 0:
    raise RuntimeError("restored test failed")
if sha256(test) != test_hash:
    raise RuntimeError("test changed during proof")
(out / "oauth-https-redirect-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "disable only exact HTTPS OAuth interception before Gecko load, then restore",
    "negative_exit": negative["exit"],
    "negative_tests": negative["tests"],
    "negative_assertion_failures": negative["failures"],
    "restored_green_exit": restored["exit"],
    "restored_tests": restored["tests"],
    "restored_failures": restored["failures"],
    "restored_source_sha256": sha256(source),
    "unchanged_test_sha256": test_hash,
    "negative_log": negative["log"],
    "negative_junit_xml": negative["junit_xml"],
    "restored_log": restored["log"],
    "restored_junit_xml": restored["junit_xml"],
}, indent=2) + "\n", encoding="utf-8")
print("HTTPS OAuth callback interception red / exact restore green verified", flush=True)
