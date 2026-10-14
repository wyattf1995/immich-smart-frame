#!/usr/bin/env python3
"""Mutation proof for the constrained native-capability live camera path.

The Node half is intentionally light enough to run locally. The JVM half is
opt-in because it invokes Gradle and must run through the admitted Android build
runner. Both mutate production source, require the named guard to fail, restore
the exact bytes, and require the guard to pass again.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT_DEFAULT = Path(__file__).resolve().parents[1]
CAPABILITY_TEST_CLASS = "com.wyattfleming.touchframe.media.TouchLiveCameraCapabilityTest"
CAPABILITY_TEST_CASE = "nonceMayBeConsumedOnlyOnceWhileItsGenerationIsVisible"
MODE_TEST_CLASS = "com.wyattfleming.touchframe.media.TouchCameraModeGateTest"
MODE_TEST_CASE = "selectionAndLiveFailureNeverStartSnapshotWork"
LIFECYCLE_TEST_CLASS = "com.wyattfleming.touchframe.media.TouchCameraLiveStatusGateTest"
LIFECYCLE_TEST_CASE = "onlyCurrentFailedLiveSurfaceRestoresNativeControls"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command: list[str], cwd: Path, log: Path) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log.write_text(result.stdout)
    return result


def require(result: subprocess.CompletedProcess[str], description: str) -> None:
    if result.returncode:
        raise RuntimeError(f"{description} failed; inspect retained log")


def verify_static_contract(root: Path) -> None:
    bridge = (root / "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraBridge.kt").read_text()
    policy = (root / "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraCapability.kt").read_text()
    manifest = (root / "frameos/touchframe/src/main/assets/frame-live-camera/manifest.json").read_text()
    page = (root / "frameos/touchframe/src/main/assets/frame-live-camera/live.js").read_text()
    required = {
        "bridge_extension_base_url": "resolveLivePage(installed.metaData.baseUrl)",
        "bridge_extension_identity": "sender.webExtension.id != EXTENSION_ID",
        "bridge_session_gate": "senderSession === activeSession && sender.isTopLevel",
        "bridge_single_use": "capability.consume(",
        "bridge_visibility_gate": "capability.isCurrent(current.generation, current.entityId, current.nonce, visible)",
        "bridge_timeout": "START_TIMEOUT_MILLIS = 12_000L",
        "policy_exact_page": 'uri.path == "/live.html"',
        "policy_allowlist": "fun allowsCamera(entityId: String): Boolean = entityId in cameras",
        "page_webrtc_config": 'type: "camera/webrtc/get_client_config"',
        "page_webrtc_offer": 'type: "camera/webrtc/offer"',
        "page_webrtc_candidate": 'type: "camera/webrtc/candidate"',
        "page_actual_event_shape": "this.handleOfferEvent(message.event)",
        "page_nest_data_channel": "this.peer.createDataChannel(config.dataChannel)",
        "page_playing_event": 'this.video.addEventListener("playing"',
        "page_unsubscribe": 'type: "unsubscribe_events"',
        "page_startup_timeout": "STARTUP_TIMEOUT_MS = 12000",
        "page_maximum_lifetime": "MAX_LIVE_MS = 5 * 60 * 1000",
    }
    sources = {"bridge": bridge, "policy": policy, "page": page}
    for name, needle in required.items():
        source = sources["bridge" if name.startswith("bridge") else "policy" if name.startswith("policy") else "page"]
        if needle not in source:
            raise RuntimeError(f"missing {name}: {needle}")
    for forbidden in ("content_scripts", "nativeMessagingFromContent"):
        if forbidden in manifest:
            raise RuntimeError(f"forbidden extension exposure: {forbidden}")
    for forbidden in ("localStorage", "sessionStorage", "console.", "?access_token", "#access_token"):
        if forbidden in page:
            raise RuntimeError(f"forbidden page token/storage/logging pattern: {forbidden}")


def require_unique_mutation_target(path: Path, before: str) -> None:
    source = path.read_text()
    if source.count(before) != 1:
        raise RuntimeError(f"expected exactly one mutation target in {path}")


def mutate_once(path: Path, before: str, after: str) -> bytes:
    require_unique_mutation_target(path, before)
    original = path.read_bytes()
    source = original.decode()
    path.write_text(source.replace(before, after, 1))
    return original


def read_junit_result(result_dir: Path, test_class: str, test_case: str, retained_xml: Path | None = None) -> dict[str, int | bool]:
    xml = result_dir / f"TEST-{test_class}.xml"
    if not xml.exists():
        raise RuntimeError("missing named JVM test XML")
    if retained_xml is not None:
        shutil.copy2(xml, retained_xml)
    suite = ET.parse(xml).getroot()
    target = next((case for case in suite.iter("testcase") if case.attrib.get("name") == test_case), None)
    if target is None:
        raise RuntimeError("named JVM capability guard did not execute")
    result: dict[str, int | bool] = {key: int(suite.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    result["target_assertion_failure"] = target.find("failure") is not None
    return result


def jvm_command() -> list[str]:
    return ["./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks", ":touchframe:testDebugUnitTest", "--tests", CAPABILITY_TEST_CLASS, "--tests", MODE_TEST_CLASS, "--tests", LIFECYCLE_TEST_CLASS]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("repo", nargs="?", type=Path, default=ROOT_DEFAULT)
    parser.add_argument("--run-jvm", action="store_true", help="requires an admitted Android build runner")
    parser.add_argument("--report-dir", type=Path, default=Path("reports/touch-live-camera"))
    args = parser.parse_args()
    root = args.repo.resolve()
    report_dir = (root / args.report_dir).resolve()
    report_dir.mkdir(parents=True, exist_ok=True)
    node = shutil.which("node")
    if node is None:
        raise SystemExit("node is required for the live WebRTC protocol proof")
    page = root / "frameos/touchframe/src/main/assets/frame-live-camera/live.js"
    policy = root / "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraCapability.kt"
    camera_view = root / "frameos/touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchCameraView.kt"
    test = root / "frameos/touchframe/src/test/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraCapabilityTest.kt"
    mode_test = root / "frameos/touchframe/src/test/kotlin/com/wyattfleming/touchframe/media/TouchCameraModeGateTest.kt"
    lifecycle_test = root / "frameos/touchframe/src/test/kotlin/com/wyattfleming/touchframe/media/TouchCameraLiveStatusGateTest.kt"
    receipt: dict[str, object] = {
        "proof": "touch-live-camera-mutation", "node": {}, "jvm": {"requested": args.run_jvm},
        "source_hashes_before": {"page": sha256(page), "policy": sha256(policy), "camera_view": sha256(camera_view), "test": sha256(test), "mode_test": sha256(mode_test), "lifecycle_test": sha256(lifecycle_test)},
    }

    verify_static_contract(root)
    # Keep all source mutations deterministic even when the JVM lane is not run locally.
    require_unique_mutation_target(page, "this.handleOfferEvent(message.event)")
    require_unique_mutation_target(page, 'this.milestone("socket")')
    require_unique_mutation_target(policy, "visible && !consumed && active?.let")
    require_unique_mutation_target(camera_view, "@Synchronized fun selectOrPrepareLive() {\n        snapshotRequested = false")
    require_unique_mutation_target(camera_view, "status == TouchLiveCameraBridge.Status.FAILED")
    extension_baseline = run([node, str(root / "scripts/tests/test-touch-live-extension-page.mjs")], root, report_dir / "extension-baseline.log")
    require(extension_baseline, "packaged extension-page binding guard")
    baseline = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-baseline.log")
    require(baseline, "Node baseline protocol guard")
    original_page = mutate_once(page, "this.handleOfferEvent(message.event)", "this.handleOfferEvent(message.event.data)")
    try:
        red = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-red.log")
        if red.returncode == 0:
            raise RuntimeError("Node protocol guard stayed green after direct-event mutation")
        if "direct HA event payload delivers queued remote ICE" not in red.stdout:
            raise RuntimeError("Node mutation did not fail the intended direct-event assertion")
    finally:
        page.write_bytes(original_page)
    green = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-green.log")
    require(green, "Node restored protocol guard")
    original_page = mutate_once(page, 'this.milestone("socket")', 'this.milestone("auth")')
    try:
        milestone_red = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-milestone-red.log")
        if milestone_red.returncode == 0:
            raise RuntimeError("Node milestone guard stayed green after socket milestone mutation")
        if "an opened signaling socket emits only its bounded milestone" not in milestone_red.stdout:
            raise RuntimeError("Node milestone mutation did not fail the intended bounded-diagnostic assertion")
    finally:
        page.write_bytes(original_page)
    milestone_green = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-milestone-green.log")
    require(milestone_green, "Node restored milestone guard")
    receipt["node"] = {
        "extension_baseline": extension_baseline.returncode,
        "baseline": baseline.returncode,
        "red": red.returncode,
        "green": green.returncode,
        "milestone_red": milestone_red.returncode,
        "milestone_green": milestone_green.returncode,
    }

    if args.run_jvm:
        frame = root / "frameos"
        result_dir = frame / "touchframe/build/test-results/testDebugUnitTest"
        baseline = run(jvm_command(), frame, report_dir / "jvm-baseline.log")
        require(baseline, "JVM capability baseline")
        baseline_xml = read_junit_result(result_dir, CAPABILITY_TEST_CLASS, CAPABILITY_TEST_CASE, report_dir / "jvm-capability-baseline.xml")
        mode_baseline_xml = read_junit_result(result_dir, MODE_TEST_CLASS, MODE_TEST_CASE, report_dir / "jvm-mode-baseline.xml")
        lifecycle_baseline_xml = read_junit_result(result_dir, LIFECYCLE_TEST_CLASS, LIFECYCLE_TEST_CASE, report_dir / "jvm-lifecycle-baseline.xml")
        original_policy = mutate_once(policy, "visible && !consumed && active?.let", "visible && active?.let")
        try:
            red = run(jvm_command(), frame, report_dir / "jvm-red.log")
            if red.returncode == 0:
                raise RuntimeError("JVM single-use guard stayed green after consumed-gate mutation")
            red_xml = read_junit_result(result_dir, CAPABILITY_TEST_CLASS, CAPABILITY_TEST_CASE, report_dir / "jvm-capability-red.xml")
            if not red_xml["target_assertion_failure"] or red_xml["errors"] or red_xml["skipped"]:
                raise RuntimeError("JVM mutation did not record the exact assertion failure without errors/skips")
        finally:
            policy.write_bytes(original_policy)
        green = run(jvm_command(), frame, report_dir / "jvm-green.log")
        require(green, "JVM restored capability guard")
        green_xml = read_junit_result(result_dir, CAPABILITY_TEST_CLASS, CAPABILITY_TEST_CASE, report_dir / "jvm-capability-green.xml")
        if green_xml["failures"] or green_xml["errors"] or green_xml["skipped"]:
            raise RuntimeError("JVM restored capability guard is not fully green")
        original_camera_view = mutate_once(
            camera_view,
            "@Synchronized fun selectOrPrepareLive() {\n        snapshotRequested = false",
            "@Synchronized fun selectOrPrepareLive() {\n        snapshotRequested = true",
        )
        try:
            mode_red = run(jvm_command(), frame, report_dir / "jvm-mode-red.log")
            if mode_red.returncode == 0:
                raise RuntimeError("JVM mode gate stayed green after explicit-snapshot reset mutation")
            mode_red_xml = read_junit_result(result_dir, MODE_TEST_CLASS, MODE_TEST_CASE, report_dir / "jvm-mode-red.xml")
            if not mode_red_xml["target_assertion_failure"] or mode_red_xml["errors"] or mode_red_xml["skipped"]:
                raise RuntimeError("JVM mode mutation did not record the exact assertion failure without errors/skips")
        finally:
            camera_view.write_bytes(original_camera_view)
        mode_green = run(jvm_command(), frame, report_dir / "jvm-mode-green.log")
        require(mode_green, "JVM restored mode gate")
        mode_green_xml = read_junit_result(result_dir, MODE_TEST_CLASS, MODE_TEST_CASE, report_dir / "jvm-mode-green.xml")
        if mode_green_xml["failures"] or mode_green_xml["errors"] or mode_green_xml["skipped"]:
            raise RuntimeError("JVM restored mode gate is not fully green")
        original_camera_view = mutate_once(camera_view, "status == TouchLiveCameraBridge.Status.FAILED", "status != TouchLiveCameraBridge.Status.FAILED")
        try:
            lifecycle_red = run(jvm_command(), frame, report_dir / "jvm-lifecycle-red.log")
            if lifecycle_red.returncode == 0:
                raise RuntimeError("JVM live lifecycle gate stayed green after failure-state mutation")
            lifecycle_red_xml = read_junit_result(result_dir, LIFECYCLE_TEST_CLASS, LIFECYCLE_TEST_CASE, report_dir / "jvm-lifecycle-red.xml")
            if not lifecycle_red_xml["target_assertion_failure"] or lifecycle_red_xml["errors"] or lifecycle_red_xml["skipped"]:
                raise RuntimeError("JVM lifecycle mutation did not record the exact assertion failure without errors/skips")
        finally:
            camera_view.write_bytes(original_camera_view)
        lifecycle_green = run(jvm_command(), frame, report_dir / "jvm-lifecycle-green.log")
        require(lifecycle_green, "JVM restored live lifecycle gate")
        lifecycle_green_xml = read_junit_result(result_dir, LIFECYCLE_TEST_CLASS, LIFECYCLE_TEST_CASE, report_dir / "jvm-lifecycle-green.xml")
        if lifecycle_green_xml["failures"] or lifecycle_green_xml["errors"] or lifecycle_green_xml["skipped"]:
            raise RuntimeError("JVM restored live lifecycle gate is not fully green")
        receipt["jvm"] = {"requested": True, "capability": {"baseline": baseline_xml, "red": red_xml, "green": green_xml}, "mode": {"baseline": mode_baseline_xml, "red": mode_red_xml, "green": mode_green_xml}, "lifecycle": {"baseline": lifecycle_baseline_xml, "red": lifecycle_red_xml, "green": lifecycle_green_xml}}

    receipt["source_hashes_after"] = {"page": sha256(page), "policy": sha256(policy), "camera_view": sha256(camera_view), "test": sha256(test), "mode_test": sha256(mode_test), "lifecycle_test": sha256(lifecycle_test)}
    if receipt["source_hashes_before"] != receipt["source_hashes_after"]:
        raise RuntimeError("proof did not restore exact source and test bytes")
    (report_dir / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    suffix = "Node + JVM" if args.run_jvm else "Node; JVM pending admitted runner"
    print(f"touch live camera mutation proof: PASS ({suffix})")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"touch live camera mutation proof: FAIL: {error}", file=sys.stderr)
        raise
