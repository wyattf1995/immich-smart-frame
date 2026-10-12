#!/usr/bin/env python3
"""Prove the opt-in Home and dashboard reparenting guards before exact restoration."""
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
command_prefix = [
    "./gradlew", "--no-daemon", "--max-workers=2", "-PframeosTarget=legacyArmv7",
    ":app:testDebugUnitTest", "--tests",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_regular(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")


def copy_and_assert_junit(
    result_name: str,
    label: str,
    expected_cases: int,
    expected_failures: int,
    failed_case_names: set[str],
) -> str:
    candidates = list(report_dir.rglob(result_name))
    assert len(candidates) == 1, candidates
    copied = out / f"{label}.xml"
    shutil.copy2(candidates[0], copied)
    root_element = ElementTree.parse(copied).getroot()
    cases = list(root_element.iter("testcase"))
    failures = [failure for case in cases for failure in case.findall("failure")]
    errors = [error for case in cases for error in case.findall("error")]
    assert len(cases) == expected_cases, len(cases)
    assert len(failures) == expected_failures, len(failures)
    assert not errors, errors
    if expected_failures:
        actual_failed = {case.get("name") for case in cases if case.find("failure") is not None}
        assert actual_failed == failed_case_names, actual_failed
        for failure in failures:
            detail = (failure.get("message") or "") + (failure.text or "")
            assert failure.get("type") == "java.lang.AssertionError", detail
    return copied.name


def run(
    test_class: str,
    result_name: str,
    label: str,
    expected_cases: int,
    expected_failures: int,
    failed_case_names: set[str],
) -> tuple[int, str, str]:
    if report_dir.exists():
        shutil.rmtree(report_dir)
    log = out / f"{label}.log"
    with log.open("w") as stream:
        result = subprocess.run(command_prefix + [test_class], cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    return (
        result.returncode,
        log.read_text(),
        copy_and_assert_junit(
            result_name,
            label,
            expected_cases,
            expected_failures,
            failed_case_names,
        ),
    )


def prove_hidden_home_policy() -> dict[str, object]:
    source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebHomeAttachmentPolicy.kt"
    test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/web/FrameWebHomeAttachmentPolicyTest.kt"
    require_regular(source)
    require_regular(test)
    original = source.read_bytes()
    original_hash = sha256(source)
    test_hash = sha256(test)
    before = '''    fun detachWarmHomeWhenShowing(slot: FrameWebSlot): Boolean =
        detachHiddenHomeAssistant && slot != FrameWebSlot.HOME_ASSISTANT

    fun allowsBackgroundPreload(slot: FrameWebSlot): Boolean =
        !(detachHiddenHomeAssistant && slot == FrameWebSlot.HOME_ASSISTANT)
'''
    after = '''    fun detachWarmHomeWhenShowing(slot: FrameWebSlot): Boolean = false

    fun allowsBackgroundPreload(slot: FrameWebSlot): Boolean = true
'''
    try:
        text = original.decode("utf-8")
        if text.count(before) != 1 or after in text:
            raise RuntimeError("expected one untouched opt-in hidden Home policy implementation")
        source.write_text(text.replace(before, after), encoding="utf-8")
        negative_exit, negative_log, negative_xml = run(
            "com.wyattfleming.frameos.web.FrameWebHomeAttachmentPolicyTest",
            "TEST-com.wyattfleming.frameos.web.FrameWebHomeAttachmentPolicyTest.xml",
            "hidden-home-policy-source-red",
            expected_cases=2,
            expected_failures=1,
            failed_case_names={"opt in detaches hidden Home and rejects its background preload"},
        )
        assert negative_exit != 0, negative_log
    finally:
        source.write_bytes(original)
        assert source.read_bytes() == original
        assert sha256(source) == original_hash

    restored_exit, restored_log, restored_xml = run(
        "com.wyattfleming.frameos.web.FrameWebHomeAttachmentPolicyTest",
        "TEST-com.wyattfleming.frameos.web.FrameWebHomeAttachmentPolicyTest.xml",
        "hidden-home-policy-restored-green",
        expected_cases=2,
        expected_failures=0,
        failed_case_names=set(),
    )
    assert restored_exit == 0, restored_log
    assert sha256(test) == test_hash
    return {
        "recipe": "opt-in hidden Home attachment policy",
        "negative_exit": negative_exit,
        "negative_tests": 2,
        "negative_assertion_failures": 1,
        "restored_green_exit": restored_exit,
        "restored_tests": 2,
        "restored_failures": 0,
        "original_source_sha256": original_hash,
        "restored_source_sha256": sha256(source),
        "unchanged_test_sha256": test_hash,
        "negative_log": "hidden-home-policy-source-red.log",
        "negative_junit_xml": negative_xml,
        "restored_log": "hidden-home-policy-restored-green.log",
        "restored_junit_xml": restored_xml,
    }


def prove_dashboard_reparenting() -> dict[str, object]:
    source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/ui/TouchDashboardView.kt"
    test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/ui/TouchDashboardContractTest.kt"
    require_regular(source)
    require_regular(test)
    original = source.read_bytes()
    original_hash = sha256(source)
    test_hash = sha256(test)
    before = '''    private fun rebuild() {
        detachReusableChildren()
        content.removeAllViews()
'''
    after = '''    private fun rebuild() {
        content.removeAllViews()
'''
    try:
        text = original.decode("utf-8")
        if text.count(before) != 1 or after in text:
            raise RuntimeError("expected one untouched dashboard reusable-child detach call")
        source.write_text(text.replace(before, after), encoding="utf-8")
        negative_exit, negative_log, negative_xml = run(
            "com.wyattfleming.frameos.ui.TouchDashboardContractTest",
            "TEST-com.wyattfleming.frameos.ui.TouchDashboardContractTest.xml",
            "touch-dashboard-detach-source-red",
            expected_cases=5,
            expected_failures=1,
            failed_case_names={"dashboardDetachesReusableViewsBeforeModeOrThemeRebuilds"},
        )
        assert negative_exit != 0, negative_log
    finally:
        source.write_bytes(original)
        assert source.read_bytes() == original
        assert sha256(source) == original_hash

    restored_exit, restored_log, restored_xml = run(
        "com.wyattfleming.frameos.ui.TouchDashboardContractTest",
        "TEST-com.wyattfleming.frameos.ui.TouchDashboardContractTest.xml",
        "touch-dashboard-detach-restored-green",
        expected_cases=5,
        expected_failures=0,
        failed_case_names=set(),
    )
    assert restored_exit == 0, restored_log
    assert sha256(test) == test_hash
    return {
        "recipe": "dashboard reusable-child detachment before rebuild",
        "negative_exit": negative_exit,
        "negative_tests": 5,
        "negative_assertion_failures": 1,
        "restored_green_exit": restored_exit,
        "restored_tests": 5,
        "restored_failures": 0,
        "original_source_sha256": original_hash,
        "restored_source_sha256": sha256(source),
        "unchanged_test_sha256": test_hash,
        "negative_log": "touch-dashboard-detach-source-red.log",
        "negative_junit_xml": negative_xml,
        "restored_log": "touch-dashboard-detach-restored-green.log",
        "restored_junit_xml": restored_xml,
    }


def prove_dashboard_photo_label_contrast() -> dict[str, object]:
    source = frame / "app/src/main/kotlin/com/wyattfleming/frameos/ui/TouchDashboardView.kt"
    test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/ui/TouchDashboardContractTest.kt"
    require_regular(source)
    require_regular(test)
    original = source.read_bytes()
    original_hash = sha256(source)
    test_hash = sha256(test)
    before = "setColor(style.surface)"
    after = "setColor(0xb30d1d31.toInt())"
    try:
        text = original.decode("utf-8")
        if text.count(before) != 1 or after in text:
            raise RuntimeError("expected one themed dashboard Photos-label surface")
        source.write_text(text.replace(before, after, 1), encoding="utf-8")
        negative_exit, negative_log, negative_xml = run(
            "com.wyattfleming.frameos.ui.TouchDashboardContractTest",
            "TEST-com.wyattfleming.frameos.ui.TouchDashboardContractTest.xml",
            "touch-dashboard-photo-label-contrast-source-red",
            expected_cases=5,
            expected_failures=1,
            failed_case_names={"navigationAndPhotoLabelsRemainReachableAndLegible"},
        )
        assert negative_exit != 0, negative_log
    finally:
        source.write_bytes(original)
        assert source.read_bytes() == original
        assert sha256(source) == original_hash

    restored_exit, restored_log, restored_xml = run(
        "com.wyattfleming.frameos.ui.TouchDashboardContractTest",
        "TEST-com.wyattfleming.frameos.ui.TouchDashboardContractTest.xml",
        "touch-dashboard-photo-label-contrast-restored-green",
        expected_cases=5,
        expected_failures=0,
        failed_case_names=set(),
    )
    assert restored_exit == 0, restored_log
    assert sha256(test) == test_hash
    return {
        "recipe": "themed Photos label surface with contrasting bold ink",
        "negative_exit": negative_exit,
        "negative_tests": 5,
        "negative_assertion_failures": 1,
        "restored_green_exit": restored_exit,
        "restored_tests": 5,
        "restored_failures": 0,
        "original_source_sha256": original_hash,
        "restored_source_sha256": sha256(source),
        "unchanged_test_sha256": test_hash,
        "negative_log": "touch-dashboard-photo-label-contrast-source-red.log",
        "negative_junit_xml": negative_xml,
        "restored_log": "touch-dashboard-photo-label-contrast-restored-green.log",
        "restored_junit_xml": restored_xml,
    }


hidden_home = prove_hidden_home_policy()
dashboard = prove_dashboard_reparenting()
dashboard_photo_label_contrast = prove_dashboard_photo_label_contrast()
(out / "touch-dashboard-source-proof.json").write_text(json.dumps({
    "passed": True,
    "hidden_home_policy": hidden_home,
    "dashboard_reparenting": dashboard,
    "dashboard_photo_label_contrast": dashboard_photo_label_contrast,
}, indent=2) + "\n", encoding="utf-8")
print("touch dashboard source-only red / exact restore green verified", flush=True)
