#!/usr/bin/env python3
"""Run source-only red/green proofs for the bounded native TouchFrame shell."""
from __future__ import annotations

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
module = frame / "touchframe"
out = root / "reports" / "touchframe-android" / "source-proof"
out.mkdir(parents=True, exist_ok=True)
result_dir = module / "build" / "test-results" / "testDebugUnitTest"
bridge_source = frame / "app" / "src/main/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridge.kt"
bridge_test = frame / "app" / "src/test/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridgePreparedAttachmentContractTest.kt"
bridge_result_dir = frame / "frame-shared" / "build" / "test-results" / "testDebugUnitTest"
bridge_class = "com.wyattfleming.frameos.photos.FramePhotoBridgePreparedAttachmentContractTest"
suites = {
    "shell": (module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayoutTest.kt", "com.wyattfleming.touchframe.ui.TouchShellLayoutTest"),
    "parser": (module / "src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeParser.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeParserTest.kt", "com.wyattfleming.touchframe.home.TouchHomeParserTest"),
    "repository": (module / "src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepository.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepositoryTest.kt", "com.wyattfleming.touchframe.home.TouchHomeRepositoryTest"),
    "media": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchMediaLoadStateTest.kt", "com.wyattfleming.touchframe.media.TouchMediaLoadStateTest"),
    "offline": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotos.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoLifecycleTest.kt", "com.wyattfleming.touchframe.media.TouchOfflinePhotoLifecycleTest"),
    "blur": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotos.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflineBlurPlanTest.kt", "com.wyattfleming.touchframe.media.TouchOfflineBlurPlanTest"),
    "offline_store": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoStore.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoStoreScopeGateTest.kt", "com.wyattfleming.touchframe.media.TouchOfflinePhotoStoreScopeGateTest"),
    "hidden_commit": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoStore.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoCommitContractTest.kt", "com.wyattfleming.touchframe.media.TouchOfflinePhotoCommitContractTest"),
    "companion": (module / "src/main/kotlin/com/wyattfleming/touchframe/control/TouchCompanionController.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/control/TouchCompanionControllerTest.kt", "com.wyattfleming.touchframe.control.TouchCompanionControllerTest"),
    "destination_order": (module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchDestination.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchDestinationOrderTest.kt", "com.wyattfleming.touchframe.ui.TouchDestinationOrderTest"),
    "settings_policy": (module / "src/main/kotlin/com/wyattfleming/touchframe/control/TouchRemoteSettingsPolicy.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/control/TouchRemoteSettingsPolicyTest.kt", "com.wyattfleming.touchframe.control.TouchRemoteSettingsPolicyTest"),
    "provisioning": (module / "src/main/kotlin/com/wyattfleming/touchframe/control/TouchProvisionContract.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/control/TouchProvisionContractTest.kt", "com.wyattfleming.touchframe.control.TouchProvisionContractTest"),
    "scope": (module / "src/main/kotlin/com/wyattfleming/touchframe/concurrent/TouchTaskScope.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/concurrent/TouchTaskScopeTest.kt", "com.wyattfleming.touchframe.concurrent.TouchTaskScopeTest"),
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_file(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")


def static_contract() -> dict[str, object]:
    shell = suites["shell"][0].read_text()
    repo = suites["repository"][0].read_text()
    parser = suites["parser"][0].read_text()
    media = suites["media"][0].read_text()
    offline = suites["offline"][0].read_text()
    blur = suites["blur"][0].read_text()
    offline_store = suites["offline_store"][0].read_text()
    hidden_commit = suites["hidden_commit"][0].read_text()
    companion = suites["companion"][0].read_text()
    destination_order = suites["destination_order"][0].read_text()
    settings_policy = suites["settings_policy"][0].read_text()
    provisioning = suites["provisioning"][0].read_text()
    scope = suites["scope"][0].read_text()
    required = {
        "media-only edge gesture": "destination.isImmersiveMedia &&",
        "48dp edge target": "EDGE_WIDTH_DP = 48",
        "56dp horizontal threshold": "HORIZONTAL_THRESHOLD_DP = 56",
        "32dp vertical tolerance": "VERTICAL_TOLERANCE_DP = 32",
        "direct state API": "/api/states/$entityId",
        "direct calendar API": "/api/calendars/$calendarId",
        "bounded request workers": "MAX_REQUEST_WORKERS = 2",
        "cancel operation generation gate": "!acceptOperation(operation)",
        "calendar event cap": "MAX_EVENTS_PER_CALENDAR = 200",
        "same-url crash retry": "if (configuredUrl == url) return if (contentProcessClosed)",
        "pause lifecycle gate": "if (closed || !resumed) return\n            resumed = false",
        "Photos-active fallback gate": "active = value && attachment != null",
        "bounded blurred backdrop": "const val MAX_BACKDROP_EDGE = 128",
        "uncropped foreground mapping": "TouchOfflineBlurPlan.forForeground",
        "offline scope generation": "class TouchOfflineStoreScopeGate",
        "hidden commit cancellation gate": "!task.isCancelled && !Thread.currentThread().isInterrupted",
        "worker credential reread": "if (credentials() != currentCredentials)",
        "worker event acceptance": "onEvent(it, expectedGeneration) { isCurrent(expectedGeneration) }",
        "main callback timeout cancellation": "task.cancel(false)",
        "dispatch-time command expiry": "if (nowMillis() > command.expiresAtMillis)",
        "permanent Home ordering anchor": "add(TouchDestination.HOME)",
        "Birds requires local configuration": "it != TouchDestination.BIRDS || birdsConfigured",
        "strict monotonic settings revision": "incomingRevision >= 0 && incomingRevision > currentRevision",
        "provisioning collection cap": "private const val MAX_CALENDARS = 4",
    }
    combined = shell + repo + parser + media + offline + blur + offline_store + hidden_commit + companion + destination_order + settings_policy + provisioning + scope
    missing = [name for name, needle in required.items() if needle not in combined]
    if missing:
        raise RuntimeError(f"missing TouchFrame source contract markers: {missing}")
    forbidden = ("<iframe", "WebView", "loadUri(")
    found = [needle for needle in forbidden if needle in repo or needle in parser]
    if found:
        raise RuntimeError(f"direct Home data layer contains web navigation primitive(s): {found}")
    return {"requiredMarkers": required, "forbiddenHomePrimitives": list(forbidden)}


def test_count(path: Path) -> int:
    return path.read_text().count("@Test")


for source, test, _ in suites.values():
    require_file(source)
    require_file(test)
require_file(bridge_source)
require_file(bridge_test)
static = static_contract()
source_bytes = {name: source.read_bytes() for name, (source, _, _) in suites.items()}
source_hashes = {name: hashlib.sha256(value).hexdigest() for name, value in source_bytes.items()}
test_hashes = {str(test.relative_to(root)): digest(test) for _, (_, test, _) in suites.items()}
expected_tests = sum(test_count(test) for _, (_, test, _) in suites.items())
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", ":touchframe:testDebugUnitTest",
    *[arg for _, (_, _, klass) in suites.items() for arg in ("--tests", klass)],
]
bridge_command = [
    "./gradlew", "--no-daemon", "--max-workers=2", ":frame-shared:testDebugUnitTest",
    "--tests", bridge_class,
]


def run(label: str, expected_failures: set[str]) -> dict[str, object]:
    if result_dir.exists():
        shutil.rmtree(result_dir)
    log = out / f"{label}.log"
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    cases: list[ET.Element] = []
    for _, (_, _, klass) in suites.items():
        xmls = list(result_dir.rglob(f"TEST-{klass}.xml"))
        if len(xmls) != 1:
            raise RuntimeError(f"expected one JUnit XML for {klass}, found {xmls}")
        cases.extend(ET.parse(xmls[0]).getroot().iter("testcase"))
    failed = {case.get("name") or "" for case in cases if case.find("failure") is not None}
    errors = [case for case in cases if case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    if len(cases) != expected_tests or errors or skipped or failed != expected_failures:
        raise RuntimeError(f"unexpected {label}: tests={len(cases)} failed={failed} errors={len(errors)} skipped={len(skipped)}")
    if (result.returncode == 0) != (not expected_failures):
        raise RuntimeError(f"unexpected Gradle exit {result.returncode} for {label}")
    return {"exit": result.returncode, "tests": len(cases), "failures": len(failed), "errors": len(errors), "skipped": len(skipped), "failedCases": sorted(failed), "log": log.name}


def run_bridge(label: str, expected_failures: set[str]) -> dict[str, object]:
    if bridge_result_dir.exists():
        shutil.rmtree(bridge_result_dir)
    log = out / f"{label}.log"
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(bridge_command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    xmls = list(bridge_result_dir.rglob(f"TEST-{bridge_class}.xml"))
    if len(xmls) != 1:
        raise RuntimeError(f"expected one prepared-bridge JUnit XML, found {xmls}")
    cases = list(ET.parse(xmls[0]).getroot().iter("testcase"))
    failed = {case.get("name") or "" for case in cases if case.find("failure") is not None}
    errors = [case for case in cases if case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    if len(cases) != 1 or errors or skipped or failed != expected_failures:
        raise RuntimeError(f"unexpected {label}: tests={len(cases)} failed={failed} errors={len(errors)} skipped={len(skipped)}")
    if (result.returncode == 0) != (not expected_failures):
        raise RuntimeError(f"unexpected Gradle exit {result.returncode} for {label}")
    return {"exit": result.returncode, "tests": len(cases), "failures": len(failed), "errors": len(errors), "skipped": len(skipped), "failedCases": sorted(failed), "log": log.name}


def mutate_bridge(name: str, before: str, after: str, failed: set[str], original: bytes) -> dict[str, object]:
    text = original.decode()
    if text.count(before) != 1 or after in text:
        raise RuntimeError(f"{name} mutation marker is not uniquely patchable")
    try:
        bridge_source.write_text(text.replace(before, after, 1))
        return run_bridge(name, failed)
    finally:
        bridge_source.write_bytes(original)
        if digest(bridge_source) != hashlib.sha256(original).hexdigest():
            raise RuntimeError(f"{name} did not restore exact source bytes")


def mutate(name: str, source_name: str, before: str, after: str, failed: set[str]) -> dict[str, object]:
    source = suites[source_name][0]
    text = source_bytes[source_name].decode()
    if text.count(before) != 1 or after in text:
        raise RuntimeError(f"{name} mutation marker is not uniquely patchable")
    try:
        source.write_text(text.replace(before, after, 1))
        return run(name, failed)
    finally:
        source.write_bytes(source_bytes[source_name])
        if digest(source) != source_hashes[source_name]:
            raise RuntimeError(f"{name} did not restore exact source bytes")


bridge_source_bytes = bridge_source.read_bytes()
bridge_source_hash = hashlib.sha256(bridge_source_bytes).hexdigest()
bridge_test_hash = digest(bridge_test)

try:
    initial = run("initial-green", set())
    bridge_initial = run_bridge("prepared-bridge-initial-green", set())
    bridge_red = mutate_bridge(
        "prepared-bridge-session-gate-red",
        "if (configuredSession !== session) return false",
        "if (false) return false",
        {"prepared UI attachment preserves configured session and never activates disk scope"},
        bridge_source_bytes,
    )
    edge_red = mutate(
        "edge-media-only-red", "shell",
        "destination.isImmersiveMedia &&\n        downX in 0f..edgeWidth.toFloat()",
        "true &&\n        downX in 0f..edgeWidth.toFloat()",
        {"only media destinations accept a qualified left edge reveal"},
    )
    cap_red = mutate(
        "calendar-cap-red", "parser",
        "const val MAX_EVENTS_PER_CALENDAR = 200",
        "const val MAX_EVENTS_PER_CALENDAR = 201",
        {"calendar parser bounds malformed and excessive records"},
    )
    cancel_red = mutate(
        "cancel-generation-red", "repository",
        "active = null\n            operation.cancelled = true",
        "active = operation\n            operation.cancelled = false",
        {"cancellation during token acquisition prevents every HTTP request"},
    )
    media_red = mutate(
        "media-crash-retry-red", "media",
        "return if (contentProcessClosed) Decision.RETRY_REQUIRED else Decision.LOAD",
        "return Decision.LOAD",
        {"contentCrashRequiresExplicitRetryAndUsesTheMostRecentConfiguredUrl"},
    )
    offline_red = mutate(
        "offline-hidden-photos-red", "offline",
        "active = value && attachment != null",
        "active = attachment != null",
        {"hidden Photos never exposes a reserve bitmap"},
    )
    blur_red = mutate(
        "offline-backdrop-bound-red", "blur",
        "const val MAX_BACKDROP_EDGE = 128",
        "const val MAX_BACKDROP_EDGE = 129",
        {"backdrop is bounded while foreground remains original size"},
    )
    attachment_gate_red = mutate(
        "offline-attachment-generation-red", "offline",
        "fun isCurrent(expected: Long): Boolean = generation.get() == expected",
        "fun isCurrent(expected: Long): Boolean = true",
        {"stale same session preparation cannot enable capture"},
    )
    attachment_scope_red = mutate(
        "offline-exact-scope-red", "offline",
        "session === target && photosUrl == url && profile == requestedProfile",
        "session === target && true",
        {"same session and exact scope retain the offline attachment"},
    )
    paused_capture_red = mutate(
        "offline-paused-capture-red", "offline",
        "fun captureEnabled(): Boolean = active && !paused",
        "fun captureEnabled(): Boolean = active || false",
        {"pause suppresses trusted capture while preserving visible Photos state"},
    )
    hidden_cached_red = mutate(
        "offline-hidden-cached-red", "offline",
        "displayedAssetId != null && hiddenAssetIds.any { it.equals(displayedAssetId, ignoreCase = true) }",
        "displayedAssetId != null && false",
        {"hidden policy removes a cached matching photo but keeps unrelated fallback"},
    )
    offline_store_red = mutate(
        "offline-store-same-scope-red", "offline_store",
        "if (requestedScope == scope) return generation.get()",
        "if (requestedScope == scope) return generation.incrementAndGet()",
        {"same requested scope retains its generation for pre-view policy work"},
    )
    hidden_commit_red = mutate(
        "offline-hidden-commit-cancel-red", "hidden_commit",
        "!task.isCancelled && !Thread.currentThread().isInterrupted",
        "task.isCancelled || !Thread.currentThread().isInterrupted",
        {"canceled hidden policy operation is checked at the reserve commit boundary"},
    )
    credential_red = mutate(
        "companion-credential-reread-red", "companion",
        "if (credentials() != currentCredentials) {",
        "if (false) {",
        {"credential change during poll commits none of the old snapshot"},
    )
    event_worker_red = mutate(
        "companion-event-worker-red", "companion",
        "onEvent(it, expectedGeneration) { isCurrent(expectedGeneration) }",
        "onMain(expectedGeneration) { onEvent(it, expectedGeneration) { isCurrent(expectedGeneration) } } == true",
        {"event acceptance runs on poll worker and blocks snapshot command when persistence rejects"},
    )
    expiry_red = mutate(
        "companion-expiry-before-dispatch-red", "companion",
        "if (nowMillis() > command.expiresAtMillis)",
        "if (false)",
        {"command that expires while queued is terminally expired without dispatch"},
    )
    timeout_cancel_red = mutate(
        "companion-delayed-main-cancel-red", "companion",
        "task.cancel(false)",
        "if (false) task.cancel(false)",
        {"timed out queued callback is cancelled before a delayed main executor runs it"},
    )
    destination_order_red = mutate(
        "destination-permanent-home-red", "destination_order",
        "fun normalize(remoteOrder: List<TouchDestination>): List<TouchDestination> = buildList {\n        add(TouchDestination.HOME)",
        "fun normalize(remoteOrder: List<TouchDestination>): List<TouchDestination> = buildList {\n        add(TouchDestination.ART)",
        {
            "remote order anchors Home and removes repeated modes",
            "Home tiles preserve the rail's configured non Home order",
            "Birds is visible only when its local feed is configured",
        },
    )
    birds_visibility_red = mutate(
        "birds-local-configuration-red", "destination_order",
        "it != TouchDestination.BIRDS || birdsConfigured",
        "true",
        {"Birds is visible only when its local feed is configured"},
    )
    settings_revision_red = mutate(
        "settings-monotonic-revision-red", "settings_policy",
        "incomingRevision >= 0 && incomingRevision > currentRevision",
        "incomingRevision >= 0 && true",
        {
            "repeated and stale settings cannot revert current preferences",
            "revision comparison is safe at Long limit",
        },
    )
    provisioning_cap_red = mutate(
        "provisioning-calendar-cap-red", "provisioning",
        "private const val MAX_CALENDARS = 4",
        "private const val MAX_CALENDARS = 5",
        {"input collection bounds reject oversized provisioning before normalization"},
    )
    scope_red = mutate(
        "pause-lifecycle-red", "scope",
        "if (closed || !resumed) return\n            resumed = false",
        "if (true) return\n            resumed = false",
        {
            "queued task paused before execution never invokes its block",
            "pause interrupts a running task",
            "result predicate from a paused epoch stays false after resume",
        },
    )
    restored = run("restored-green", set())
    bridge_restored = run_bridge("prepared-bridge-restored-green", set())
    unchanged_tests = {str(test.relative_to(root)): digest(test) for _, (_, test, _) in suites.items()}
    if unchanged_tests != test_hashes or digest(bridge_test) != bridge_test_hash:
        raise RuntimeError("proof changed test bytes")
    (out / "touchframe-source-proof.json").write_text(json.dumps({
        "proof": "touchframe-native-direct-data-v2", "passed": True, "command": command,
        "sourceTestAnnotationCount": expected_tests, "initial": initial, "preparedBridgeInitial": bridge_initial,
        "preparedBridgeSessionGateRed": bridge_red, "edgeRed": edge_red,
        "calendarCapRed": cap_red, "cancelGenerationRed": cancel_red, "mediaCrashRetryRed": media_red,
        "offlineHiddenPhotosRed": offline_red,
        "offlineBackdropBoundRed": blur_red,
        "offlineAttachmentGenerationRed": attachment_gate_red,
        "offlineExactScopeRed": attachment_scope_red,
        "offlinePausedCaptureRed": paused_capture_red,
        "offlineHiddenCachedRed": hidden_cached_red,
        "offlineStoreSameScopeRed": offline_store_red,
        "offlineHiddenCommitCancelRed": hidden_commit_red,
        "credentialRereadRed": credential_red, "eventWorkerRed": event_worker_red,
        "dispatchExpiryRed": expiry_red, "delayedMainCancellationRed": timeout_cancel_red,
        "destinationPermanentHomeRed": destination_order_red,
        "birdsLocalConfigurationRed": birds_visibility_red,
        "settingsMonotonicRevisionRed": settings_revision_red,
        "provisioningCalendarCapRed": provisioning_cap_red,
        "pausedTaskEntryRed": scope_red,
        "restored": restored, "preparedBridgeRestored": bridge_restored,
        "preparedBridgeSourceSha256": bridge_source_hash,
        "preparedBridgeRestoredSourceSha256": digest(bridge_source),
        "preparedBridgeTestSha256": bridge_test_hash,
        "originalSourceSha256": source_hashes,
        "restoredSourceSha256": {name: digest(source) for name, (source, _, _) in suites.items()},
        "unchangedTestSha256": test_hashes, "staticContract": static,
    }, indent=2, sort_keys=True) + "\n")
finally:
    for name, (source, _, _) in suites.items():
        source.write_bytes(source_bytes[name])
    if "bridge_source_bytes" in globals():
        bridge_source.write_bytes(bridge_source_bytes)
