#!/usr/bin/env python3
"""Prove embedded OAuth callbacks use the verified MainActivity callback path."""
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
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/MainActivityEmbeddedOAuthContractTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.frameos.MainActivityEmbeddedOAuthContractTest.xml"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest",
    "--tests", "com.wyattfleming.frameos.MainActivityEmbeddedOAuthContractTest",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def method_body(text: str, signature: str) -> tuple[int, int]:
    start = text.find(signature)
    if start < 0:
        raise RuntimeError(f"missing signature: {signature}")
    open_brace = text.find("{", start)
    depth = 0
    for index in range(open_brace, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return start, index + 1
    raise RuntimeError(f"unterminated method: {signature}")


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
    if len(cases) != 3 or len(failures) != expected_failures or errors:
        raise RuntimeError(f"unexpected JUnit outcome cases={len(cases)} failures={len(failures)} errors={len(errors)}")
    failed_names = {case.get("name") for case in failures}
    expected_names = {"embeddedCallbackUsesAuthoritativeCallbackHandler"} if expected_failures else set()
    if failed_names != expected_names:
        raise RuntimeError(f"unexpected failed tests: {failed_names}")
    return {
        "exit": completed.returncode,
        "tests": len(cases),
        "failures": len(failures),
        "log": log.name,
        "junit_xml": copied.name,
    }


if not source.is_file() or source.is_symlink() or not test.is_file() or test.is_symlink():
    raise RuntimeError("required regular source or contract test missing")
original = source.read_bytes()
source_hash = sha256(source)
test_hash = sha256(test)
try:
    text = original.decode("utf-8")
    begin, end = method_body(text, "private fun beginWeatherAuthorization()")
    method = text[begin:end]
    marker = "                    handleOAuthCallbackUrl(callback)\n"
    if method.count(marker) != 1:
        raise RuntimeError("expected exactly one embedded OAuth callback handler call")
    mutated = text[:begin] + method.replace(marker, "", 1) + text[end:]
    source.write_text(mutated, encoding="utf-8")
    negative = run("embedded-oauth-callback-source-red", expected_failures=1)
    if negative["exit"] == 0:
        raise RuntimeError("negative proof unexpectedly passed")
finally:
    source.write_bytes(original)
    if sha256(source) != source_hash:
        raise RuntimeError("MainActivity exact restoration failed")

restored = run("embedded-oauth-callback-restored-green", expected_failures=0)
if restored["exit"] != 0:
    raise RuntimeError("restored contract test failed")
if sha256(test) != test_hash:
    raise RuntimeError("contract test changed during proof")

(out / "embedded-oauth-integration-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "remove only embedded OAuth authoritative callback handling",
    "original_source_sha256": source_hash,
    "restored_source_sha256": sha256(source),
    "unchanged_test_sha256": test_hash,
    "negative": negative,
    "restored": restored,
}, indent=2) + "\n", encoding="utf-8")
print("embedded OAuth callback red / exact restore green verified", flush=True)
