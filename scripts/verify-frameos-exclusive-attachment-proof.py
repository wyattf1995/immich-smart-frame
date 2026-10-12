#!/usr/bin/env python3
"""Prove Home releases an opt-in foreground Gecko attachment before reattaching."""
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
source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt"
test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebSurfaceContractTest.kt"
report_dir = frame / "app/build/test-results/testDebugUnitTest"
result_name = "TEST-com.wyattfleming.frameos.web.FrameWebSurfaceContractTest.xml"
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest", "--tests", "com.wyattfleming.frameos.web.FrameWebSurfaceContractTest",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_regular(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")


def run(label: str, expected_failures: int, expected_failure_name: str | None = None) -> tuple[int, str, str]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    candidates = list(report_dir.rglob(result_name))
    assert len(candidates) == 1, candidates
    copied = out / f"{label}.xml"
    shutil.copy2(candidates[0], copied)
    root_element = ElementTree.parse(copied).getroot()
    cases = list(root_element.iter("testcase"))
    failures = [failure for case in cases for failure in case.findall("failure")]
    errors = [error for case in cases for error in case.findall("error")]
    assert len(cases) == 15, len(cases)
    assert len(failures) == expected_failures, len(failures)
    assert not errors, errors
    if expected_failures:
        failed_names = {case.get("name") for case in cases if case.find("failure") is not None}
        assert failed_names == {expected_failure_name}, failed_names
    return result.returncode, log.read_text(), copied.name


require_regular(source)
require_regular(test)
original = source.read_bytes()
source_hash = sha256(source)
test_hash = sha256(test)
home_before = """            if (detachHiddenHomeAssistant) releaseForegroundAttachedSession()
            ensureWarmHomeAttached(managed)
"""
home_after = """            ensureWarmHomeAttached(managed)
"""
hide_before = """        detachWarmHomeWhenRequired(slot = null)
        if (detachHiddenHomeAssistant) releaseForegroundAttachedSession()
        warmHomeAssistantView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
"""
hide_after = """        detachWarmHomeWhenRequired(slot = null)
        warmHomeAssistantView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
"""


def prove_negative(
    before: str,
    after: str,
    label: str,
    expected_failure_name: str,
) -> dict[str, object]:
    text = original.decode("utf-8")
    if text.count(before) != 1:
        raise RuntimeError(f"expected one untouched guard for {label}")
    try:
        source.write_text(text.replace(before, after), encoding="utf-8")
        negative_exit, negative_log, negative_xml = run(label, 1, expected_failure_name)
        assert negative_exit != 0, negative_log
        return {
            "negative_exit": negative_exit,
            "negative_tests": 15,
            "negative_assertion_failures": 1,
            "negative_log": f"{label}.log",
            "negative_junit_xml": negative_xml,
        }
    finally:
        source.write_bytes(original)
        assert source.read_bytes() == original
        assert sha256(source) == source_hash


home = prove_negative(
    home_before,
    home_after,
    "exclusive-home-attachment-source-red",
    "opt in releases foreground before attaching Home and preserves the default warm path",
)
hide = prove_negative(
    hide_before,
    hide_after,
    "exclusive-hide-attachment-source-red",
    "opt in hide releases both Home and foreground renderer bindings",
)
restored_exit, restored_log, restored_xml = run("exclusive-attachment-restored-green", 0)
assert restored_exit == 0, restored_log
assert sha256(test) == test_hash
(out / "exclusive-attachment-source-proof.json").write_text(json.dumps({
    "passed": True,
    "recipe": "detach opt-in foreground session before Home reattachment and native hide",
    "home_transition": home,
    "hide_transition": hide,
    "restored_green_exit": restored_exit,
    "restored_tests": 15,
    "restored_failures": 0,
    "original_source_sha256": source_hash,
    "restored_source_sha256": sha256(source),
    "unchanged_test_sha256": test_hash,
    "restored_log": "exclusive-attachment-restored-green.log",
    "restored_junit_xml": restored_xml,
}, indent=2) + "\n", encoding="utf-8")
print("exclusive attachment source-only red / exact restore green verified", flush=True)
