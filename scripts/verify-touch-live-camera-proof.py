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
TEST_CLASS = "com.wyattfleming.touchframe.media.TouchLiveCameraCapabilityTest"
TEST_CASE = "nonceMayBeConsumedOnlyOnceWhileItsGenerationIsVisible"


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


def mutate_once(path: Path, before: str, after: str) -> bytes:
    original = path.read_bytes()
    source = original.decode()
    if source.count(before) != 1:
        raise RuntimeError(f"expected exactly one mutation target in {path}")
    path.write_text(source.replace(before, after, 1))
    return original


def read_junit_result(result_dir: Path) -> dict[str, int]:
    xml = result_dir / f"TEST-{TEST_CLASS}.xml"
    if not xml.exists():
        raise RuntimeError("missing named JVM test XML")
    suite = ET.parse(xml).getroot()
    if not any(case.attrib.get("name") == TEST_CASE for case in suite.iter("testcase")):
        raise RuntimeError("named JVM capability guard did not execute")
    return {key: int(suite.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}


def jvm_command() -> list[str]:
    return ["./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks", ":touchframe:testDebugUnitTest", "--tests", TEST_CLASS]


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
    test = root / "frameos/touchframe/src/test/kotlin/com/wyattfleming/touchframe/media/TouchLiveCameraCapabilityTest.kt"
    receipt: dict[str, object] = {
        "proof": "touch-live-camera-mutation", "node": {}, "jvm": {"requested": args.run_jvm},
        "source_hashes_before": {"page": sha256(page), "policy": sha256(policy), "test": sha256(test)},
    }

    verify_static_contract(root)
    baseline = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-baseline.log")
    require(baseline, "Node baseline protocol guard")
    original_page = mutate_once(page, "this.handleOfferEvent(message.event)", "this.handleOfferEvent(message.event.data)")
    try:
        red = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-red.log")
        if red.returncode == 0:
            raise RuntimeError("Node protocol guard stayed green after direct-event mutation")
    finally:
        page.write_bytes(original_page)
    green = run([node, str(root / "scripts/tests/test-touch-live-camera.mjs")], root, report_dir / "node-green.log")
    require(green, "Node restored protocol guard")
    receipt["node"] = {"baseline": baseline.returncode, "red": red.returncode, "green": green.returncode}

    if args.run_jvm:
        frame = root / "frameos"
        result_dir = frame / "touchframe/build/test-results/testDebugUnitTest"
        baseline = run(jvm_command(), frame, report_dir / "jvm-baseline.log")
        require(baseline, "JVM capability baseline")
        baseline_xml = read_junit_result(result_dir)
        original_policy = mutate_once(policy, "visible && !consumed && active?.let", "visible && active?.let")
        try:
            red = run(jvm_command(), frame, report_dir / "jvm-red.log")
            if red.returncode == 0:
                raise RuntimeError("JVM single-use guard stayed green after consumed-gate mutation")
            red_xml = read_junit_result(result_dir)
            if red_xml["failures"] + red_xml["errors"] < 1:
                raise RuntimeError("JVM mutation did not record a named failure")
        finally:
            policy.write_bytes(original_policy)
        green = run(jvm_command(), frame, report_dir / "jvm-green.log")
        require(green, "JVM restored capability guard")
        green_xml = read_junit_result(result_dir)
        if green_xml["failures"] or green_xml["errors"] or green_xml["skipped"]:
            raise RuntimeError("JVM restored capability guard is not fully green")
        receipt["jvm"] = {"requested": True, "baseline": baseline_xml, "red": red_xml, "green": green_xml}

    receipt["source_hashes_after"] = {"page": sha256(page), "policy": sha256(policy), "test": sha256(test)}
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
