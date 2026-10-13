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
    "calendar_week": (module / "src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarWeekPresentation.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarWeekPresentationTest.kt", "com.wyattfleming.touchframe.calendar.TouchCalendarWeekPresentationTest"),
    "shell": (module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayoutTest.kt", "com.wyattfleming.touchframe.ui.TouchShellLayoutTest"),
    "home_layout": (module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchHomeView.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchHomeLayoutTest.kt", "com.wyattfleming.touchframe.ui.TouchHomeLayoutTest"),
    "settings_return": (module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchSettingsReturn.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchSettingsReturnPlanTest.kt", "com.wyattfleming.touchframe.ui.TouchSettingsReturnPlanTest"),
    "parser": (module / "src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeParser.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeParserTest.kt", "com.wyattfleming.touchframe.home.TouchHomeParserTest"),
    "repository": (module / "src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepository.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepositoryTest.kt", "com.wyattfleming.touchframe.home.TouchHomeRepositoryTest"),
    "media": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchMediaLoadStateTest.kt", "com.wyattfleming.touchframe.media.TouchMediaLoadStateTest"),
    "camera_palette": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchCameraView.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchCameraPaletteTest.kt", "com.wyattfleming.touchframe.media.TouchCameraPaletteTest"),
    "camera_label": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchCameraView.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchCameraLabelTest.kt", "com.wyattfleming.touchframe.media.TouchCameraLabelTest"),
    "offline": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotos.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoLifecycleTest.kt", "com.wyattfleming.touchframe.media.TouchOfflinePhotoLifecycleTest"),
    "blur": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotos.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflineBlurPlanTest.kt", "com.wyattfleming.touchframe.media.TouchOfflineBlurPlanTest"),
    "offline_store": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoStore.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoStoreScopeGateTest.kt", "com.wyattfleming.touchframe.media.TouchOfflinePhotoStoreScopeGateTest"),
    "hidden_commit": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoStore.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchOfflinePhotoCommitContractTest.kt", "com.wyattfleming.touchframe.media.TouchOfflinePhotoCommitContractTest"),
    "companion": (module / "src/main/kotlin/com/wyattfleming/touchframe/control/TouchCompanionController.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/control/TouchCompanionControllerTest.kt", "com.wyattfleming.touchframe.control.TouchCompanionControllerTest"),
    "destination_order": (module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchDestination.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchDestinationOrderTest.kt", "com.wyattfleming.touchframe.ui.TouchDestinationOrderTest"),
    "settings_policy": (module / "src/main/kotlin/com/wyattfleming/touchframe/control/TouchRemoteSettingsPolicy.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/control/TouchRemoteSettingsPolicyTest.kt", "com.wyattfleming.touchframe.control.TouchRemoteSettingsPolicyTest"),
    "provisioning": (module / "src/main/kotlin/com/wyattfleming/touchframe/control/TouchProvisionContract.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/control/TouchProvisionContractTest.kt", "com.wyattfleming.touchframe.control.TouchProvisionContractTest"),
    "scope": (module / "src/main/kotlin/com/wyattfleming/touchframe/concurrent/TouchTaskScope.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/concurrent/TouchTaskScopeTest.kt", "com.wyattfleming.touchframe.concurrent.TouchTaskScopeTest"),
    "calendar_ranges": (module / "src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarModel.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarRangeParserTest.kt", "com.wyattfleming.touchframe.calendar.TouchCalendarRangeParserTest"),
    "calendar_parser": (module / "src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarParser.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarParserBoundsTest.kt", "com.wyattfleming.touchframe.calendar.TouchCalendarParserBoundsTest"),
    "calendar_repository": (module / "src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarRepository.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarRepositoryTest.kt", "com.wyattfleming.touchframe.calendar.TouchCalendarRepositoryTest"),
    "calendar_presentation": (module / "src/main/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarView.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/calendar/TouchCalendarPresentationTest.kt", "com.wyattfleming.touchframe.calendar.TouchCalendarPresentationTest"),
    "configuration": (module / "src/main/kotlin/com/wyattfleming/touchframe/data/TouchConfiguration.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/data/TouchConfigurationTest.kt", "com.wyattfleming.touchframe.data.TouchConfigurationTest"),
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require_file(path: Path) -> None:
    if not path.is_file() or path.is_symlink():
        raise RuntimeError(f"required regular file missing: {path}")


def static_contract() -> dict[str, object]:
    shell = suites["shell"][0].read_text()
    home_layout = suites["home_layout"][0].read_text()
    activity = module / "src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt"
    settings_return = suites["settings_return"][0].read_text()
    repo = suites["repository"][0].read_text()
    parser = suites["parser"][0].read_text()
    media = suites["media"][0].read_text()
    camera_palette = suites["camera_palette"][0].read_text()
    offline = suites["offline"][0].read_text()
    blur = suites["blur"][0].read_text()
    offline_store = suites["offline_store"][0].read_text()
    hidden_commit = suites["hidden_commit"][0].read_text()
    companion = suites["companion"][0].read_text()
    destination_order = suites["destination_order"][0].read_text()
    settings_policy = suites["settings_policy"][0].read_text()
    provisioning = suites["provisioning"][0].read_text()
    scope = suites["scope"][0].read_text()
    calendar_repository = suites["calendar_repository"][0].read_text()
    calendar_presentation = suites["calendar_presentation"][0].read_text()
    configuration = suites["configuration"][0].read_text()
    required = {
        "media-only edge gesture": "destination.isImmersiveMedia &&",
        "48dp edge target": "EDGE_WIDTH_DP = 48",
        "Home configuration relayout": "home.refreshLayoutForConfiguration(newConfig)",
        "Home measured-content configuration width": "fun contentWidthDp(screenWidthDp: Float, railWidthDp: Float): Float =",
        "Home transition scheduler": "fun needsTransition(previous: Boolean?, next: Boolean): Boolean = previous != next",
        "56dp horizontal threshold": "HORIZONTAL_THRESHOLD_DP = 56",
        "32dp vertical tolerance": "VERTICAL_TOLERANCE_DP = 32",
        "AA Settings and selected rail ink": "private const val ACCESSIBLE_RAIL_INK = -15722718",
        "Settings return task fronting": "requireNotNull(task).moveToFront()",
        "Settings return owning task lookup": "manager?.appTasks?.firstOrNull { task ->",
        "Settings return nullable task record fence": "val taskId = task.taskInfo?.id",
        "Settings return task identity fence": "taskId != null && TouchSettingsReturnPlan.isOwningTask(taskId, activity.taskId)",
        "Settings return overlay resume fence": "fun dismissOverlayAfterReturnDispatch(): Boolean = false",
        "direct state API": "/api/states/$entityId",
        "direct calendar API": "/api/calendars/$calendarId",
        "bounded request workers": "MAX_REQUEST_WORKERS = 2",
        "cancel operation generation gate": "!acceptOperation(operation)",
        "calendar event cap": "MAX_EVENTS_PER_CALENDAR = 200",
        "hidden crash has one return recovery": "if (hiddenReturnRecoveryAvailable) {",
        "bounded foreground media render deadline": "armRenderDeadline(renderDeadline.beginLoading(load, active))",
        "media deadline session fence": "token.load.sessionGeneration == sessionGeneration",
        "cross URL media session replacement": "TouchMediaLoadState.Decision.REPLACE_DOCUMENT_SESSION -> {",
        "Camera palette accessible accent": "accent = 0xFF4F9F9A.toInt(), accentInk = 0xFF101722.toInt(),\n                disabledSurface = 0xFFD7D5CE.toInt(),",
        "Camera label prefix collapse": "return tokens.take(repeatedPrefixLength) + tokens.drop(repeatedPrefixLength * 2)",
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
        "Calendar socket cleanup ownership": "invalidated.futures.toList()",
        "calendar control text scale": "const val CONTROL_TEXT_SP = 22f",
        "calendar weekday header allowance": "WEEKDAY_HEADER_MIN_HEIGHT_DP = 48",
        "Birds URL validation": "if (birds != null && !urlPolicy.isSafeConfigurationUrl(birds)) return null",
    }
    combined = shell + home_layout + activity.read_text() + settings_return + repo + parser + media + camera_palette + offline + blur + offline_store + hidden_commit + companion + destination_order + settings_policy + provisioning + scope + calendar_repository + calendar_presentation + configuration
    missing = [name for name, needle in required.items() if needle not in combined]
    if missing:
        raise RuntimeError(f"missing TouchFrame source contract markers: {missing}")
    if "Intent.FLAG_ACTIVITY_REORDER_TO_FRONT" in settings_return:
        raise RuntimeError("Settings return must not reorder a task after Settings accepts a launch")
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
    "./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks", ":touchframe:testDebugUnitTest",
    *[arg for _, (_, _, klass) in suites.items() for arg in ("--tests", klass)],
]
bridge_command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks", ":frame-shared:testDebugUnitTest",
    "--tests", bridge_class,
]
repository_climate_case = "failed refresh makes previously confirmed climate read-only"
repository_case_command = [
    "./gradlew", "--no-daemon", "--max-workers=2", "--rerun-tasks", ":touchframe:testDebugUnitTest",
    "--tests", f"{suites['repository'][2]}.{repository_climate_case}",
]


def missing_results(log: Path, exit_code: int, detail: str) -> RuntimeError:
    # Source-only builds contain no device credentials. Surface a bounded compiler
    # tail so a script failure is diagnosable without waiting for artifact export.
    tail = "\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-80:])[-12000:]
    return RuntimeError(f"{detail}; Gradle exit={exit_code}; log={log.name}\n{tail}")


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
            raise missing_results(log, result.returncode, f"expected one JUnit XML for {klass}, found {xmls}")
        cases.extend(ET.parse(xmls[0]).getroot().iter("testcase"))
    failed = {case.get("name") or "" for case in cases if case.find("failure") is not None}
    errors = [case for case in cases if case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    if len(cases) != expected_tests or errors or skipped or failed != expected_failures:
        raise RuntimeError(f"unexpected {label}: tests={len(cases)} failed={failed} errors={len(errors)} skipped={len(skipped)}")
    if (result.returncode == 0) != (not expected_failures):
        raise RuntimeError(f"unexpected Gradle exit {result.returncode} for {label}")
    return {"exit": result.returncode, "tests": len(cases), "failures": len(failed), "errors": len(errors), "skipped": len(skipped), "failedCases": sorted(failed), "log": log.name}


def run_repository_case(label: str, expected_failures: set[str]) -> dict[str, object]:
    if result_dir.exists():
        shutil.rmtree(result_dir)
    log = out / f"{label}.log"
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(repository_case_command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    klass = suites["repository"][2]
    xmls = list(result_dir.rglob(f"TEST-{klass}.xml"))
    if len(xmls) != 1:
        raise missing_results(log, result.returncode, f"expected one repository JUnit XML, found {xmls}")
    cases = list(ET.parse(xmls[0]).getroot().iter("testcase"))
    selected = [case for case in cases if case.get("name") == repository_climate_case]
    if len(cases) != 1 or len(selected) != 1:
        raise RuntimeError(f"expected exact targeted repository case once, found {[case.get('name') for case in cases]}")
    case = selected[0]
    failed = {case.get("name") or ""} if case.find("failure") is not None else set()
    if case.find("error") is not None or case.find("skipped") is not None or failed != expected_failures:
        raise RuntimeError(f"unexpected {label}: failed={failed}")
    if (result.returncode == 0) != (not expected_failures):
        raise RuntimeError(f"unexpected Gradle exit {result.returncode} for {label}")
    return {"exit": result.returncode, "tests": 1, "failures": len(failed), "errors": 0, "skipped": 0, "failedCases": sorted(failed), "log": log.name}


def run_bridge(label: str, expected_failures: set[str], klass: str = bridge_class) -> dict[str, object]:
    if bridge_result_dir.exists():
        shutil.rmtree(bridge_result_dir)
    log = out / f"{label}.log"
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run([*bridge_command[:-1], klass], cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    xmls = list(bridge_result_dir.rglob(f"TEST-{klass}.xml"))
    if len(xmls) != 1:
        raise missing_results(log, result.returncode, f"expected one prepared-bridge JUnit XML, found {xmls}")
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


def mutate_repository_case(name: str, before: str, after: str, failed: set[str]) -> dict[str, object]:
    source = suites["repository"][0]
    text = source_bytes["repository"].decode()
    if text.count(before) != 1 or after in text:
        raise RuntimeError(f"{name} mutation marker is not uniquely patchable")
    try:
        source.write_text(text.replace(before, after, 1))
        return run_repository_case(name, failed)
    finally:
        source.write_bytes(source_bytes["repository"])
        if digest(source) != source_hashes["repository"]:
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


def mutate_activity_queue() -> dict[str, object]:
    source = module / "src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt"
    original = source.read_bytes()
    before = "private val worker = TouchTaskScope.newSingleWorker()"
    after = "private val worker = Executors.newSingleThreadExecutor()"
    if original.decode().count(before) != 1 or after in original.decode():
        raise RuntimeError("activity queue mutation is not uniquely patchable")
    try:
        source.write_text(original.decode().replace(before, after, 1))
        return run("activity-cancel-queue-red", {"activity calendar worker exposes cancellation queue removal"})
    finally:
        source.write_bytes(original)
        if source.read_bytes() != original:
            raise RuntimeError("activity queue mutation did not restore source")


def mutate_calendar_closed(name: str, failed: set[str]) -> dict[str, object]:
    """Remove both closed-state fences needed to reach token acquisition after close."""
    source = suites["calendar_repository"][0]
    text = source_bytes["calendar_repository"].decode()
    replacements = (
        (
            "if (synchronized(lock) { closed }) return TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)",
            "if (false) return TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE)",
        ),
        (
            "if (closed) null else cleanupLocked(active) to Operation().also { active = it }",
            "if (false) null else cleanupLocked(active) to Operation().also { active = it }",
        ),
    )
    mutated = text
    for before, after in replacements:
        if mutated.count(before) != 1 or after in mutated:
            raise RuntimeError(f"{name} mutation marker is not uniquely patchable: {before}")
        mutated = mutated.replace(before, after, 1)
    try:
        source.write_text(mutated)
        return run(name, failed)
    finally:
        source.write_bytes(source_bytes["calendar_repository"])
        if digest(source) != source_hashes["calendar_repository"]:
            raise RuntimeError(f"{name} did not restore exact source bytes")


bridge_source_bytes = bridge_source.read_bytes()
bridge_source_hash = hashlib.sha256(bridge_source_bytes).hexdigest()
bridge_test_hash = digest(bridge_test)

packaging_manifest = frame / "app/src/main/assets/frame-photo-bridge/manifest.json"
packaging_test = frame / "app/src/test/kotlin/com/wyattfleming/frameos/photos/FramePhotoBridgePackagingContractTest.kt"
packaging_ledger = frame / "app/src/test/resources/frame-photo-bridge/packaging-ledger.properties"
packaging_files = [packaging_test, packaging_ledger, *sorted(packaging_manifest.parent.rglob("*"))]
packaging_hashes = {str(path.relative_to(root)): digest(path) for path in packaging_files if path.is_file()}
packaging_bytes = packaging_manifest.read_bytes()
packaging_class = "com.wyattfleming.frameos.photos.FramePhotoBridgePackagingContractTest"

try:
    packaging_initial = run_bridge("packaging-initial-green", set(), packaging_class)
    try:
        original = packaging_bytes.decode()
        assert original.count('"version": "1.0.9"') == 1
        packaging_manifest.write_text(original.replace('"version": "1.0.9"', '"version": "1.0.8"'))
        packaging_red = run_bridge("packaging-version-red", {"built in extension version identifies its complete packaged asset set"}, packaging_class)
    finally:
        packaging_manifest.write_bytes(packaging_bytes)
    packaging_restored = run_bridge("packaging-restored-green", set(), packaging_class)
    assert all(digest(root / name) == sha for name, sha in packaging_hashes.items())
    initial = run("initial-green", set())
    bridge_initial = run_bridge("prepared-bridge-initial-green", set())
    bridge_red = mutate_bridge(
        "prepared-bridge-session-gate-red",
        "if (configuredSession !== session) return false",
        "if (false) return false",
        {"prepared UI attachment preserves configured session and never activates disk scope"},
        bridge_source_bytes,
    )
    edge_stream_red = mutate(
        "edge-stream-ownership-red", "shell",
        "return result.ownsStream || super.dispatchTouchEvent(event)",
        "return super.dispatchTouchEvent(event)",
        {"layout binds edge policy before child dispatch"},
    )
    edge_once_red = mutate(
        "edge-stream-once-red", "shell",
        "val revealNow = !revealed && TouchShellGesture.shouldReveal(",
        "val revealNow = TouchShellGesture.shouldReveal(",
        {"blank child edge stream is owned from DOWN through reveal and terminal UP"},
    )
    kill_binding_red = mutate(
        "media-kill-binding-red", "media",
        "override fun onKill(session: GeckoSession) = reportContentProcessKilled(session)",
        "override fun onKill(session: GeckoSession) = reportContentProcessClosed(session)",
        {"productionKillRecoveryIsIdentityFencedAndCrashRemainsManual"},
    )
    kill_once_red = mutate(
        "media-kill-once-red", "media",
        "val ticket = foregroundKillRecovery ?: return null\n        foregroundKillRecovery = null",
        "val ticket = foregroundKillRecovery ?: return null\n        // ticket consumption disabled",
        {"delayedKillAfterWarmReturnRecoversOnceWithoutPaintRearming", "hideClearsTheOldTicketAndTheNextRealReturnGetsOneChance"},
    )
    kill_generation_red = mutate(
        "media-kill-generation-red", "media",
        "it == configuredUrl && ticket.generation == sessionGeneration",
        "it == configuredUrl && true /* generation fence disabled */",
        {"retainedTicketCannotRecoverADifferentSessionGeneration"},
    )
    week_preview_red = mutate(
        "calendar-week-preview-red", "calendar_week",
        "const val PREVIEW_PER_DAY = 3", "const val PREVIEW_PER_DAY = 4",
        {"groupsSevenDaysWithAllDayFirstAndBoundedPreview"},
    )
    week_details_red = mutate(
        "calendar-week-details-red", "calendar_week",
        "const val DETAILS_PER_PAGE = 12", "const val DETAILS_PER_PAGE = 13",
        {"boundsSelectedDayDetailsToTwelveEventsAndReportsTheVisibleRange", "selectedDayDetailPagesKeepEveryEventReachable"},
    )
    week_default_red = mutate(
        "calendar-week-default-red", "calendar_week",
        "val DEFAULT_MODE: TouchCalendarMode = TouchCalendarMode.WEEK",
        "val DEFAULT_MODE: TouchCalendarMode = TouchCalendarMode.AGENDA",
        {"startsInWeekModeForTouchCalendar"},
    )
    weather_location_red = mutate(
        "weather-location-provision-red", "provisioning",
        "weatherLocationLabel = weatherLocationLabel,", "weatherLocationLabel = null,",
        {"weather location propagates through bounded initial provisioning"},
    )
    edge_red = mutate(
        "edge-media-only-red", "shell",
        "destination.isImmersiveMedia &&\n        downX in 0f..edgeWidth.toFloat()",
        "true &&\n        downX in 0f..edgeWidth.toFloat()",
        {"only media destinations accept a qualified left edge reveal"},
    )
    home_first_measure_red = mutate(
        "home-layout-transition-red", "home_layout",
        "if (!TouchHomeLayout.needsTransition(rendered, next)) {",
        "if (rendered != null || !TouchHomeLayout.needsTransition(rendered, next)) {",
        {
            "layout transition emits initial and changed classes exactly once",
            "stale render does not discard a pending display layout transition",
        },
    )
    rail_viewport_red = mutate(
        "rail-scroll-viewport-red", "shell",
        "railViewport.addView(rail, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))",
        "addView(rail, LayoutParams(dp(80), LayoutParams.MATCH_PARENT, Gravity.START))",
        {"short rails attach through a fill viewport"},
    )
    shell_contrast_red = mutate(
        "shell-contrast-red", "shell",
        "private const val ACCESSIBLE_RAIL_INK = -15722718",
        "private const val ACCESSIBLE_RAIL_INK = -922140",
        {"selected and Settings rail ink satisfy AA contrast in both palettes"},
    )
    settings_return_red = mutate(
        "settings-return-overlay-red", "settings_return",
        "fun dismissOverlayAfterReturnDispatch(): Boolean = false",
        "fun dismissOverlayAfterReturnDispatch(): Boolean = true",
        {"return dispatch retains overlay until the host resumes"},
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
    climate_stale_red = mutate_repository_case(
        "climate-stale-gate-red",
        """private fun markClimateStale(connection: TouchConnection) = synchronized(operationLock) {
        lastConfirmedClimate = null
        climateConnection = connection
    }""",
        """private fun markClimateStale(connection: TouchConnection) = synchronized(operationLock) {
        }""",
        {repository_climate_case},
    )
    media_red = mutate(
        "media-hidden-return-recovery-red", "media",
        "if (hiddenReturnRecoveryAvailable) {",
        "if (false) {",
        {"hiddenContentCrashRecoversExactlyOnceWhenTheUserReturns", "hiddenDeliveredClosureConsumesAllRecoveryBeforeReplacement"},
    )
    media_paint_readiness_red = mutate(
        "media-contentful-paint-readiness-red", "media",
        "if (!success) reportFailure(session, context.getString(R.string.touch_media_load_failed, label))",
        "if (success) reportReady(session)\n                else reportFailure(session, context.getString(R.string.touch_media_load_failed, label))",
        {"successfulNavigationWaitsForContentfulPaintBeforeRemovingTheCover"},
    )
    media_cross_url_session_red = mutate(
        "media-cross-url-session-red", "media",
        """            TouchMediaLoadState.Decision.RECOVER_RETAINED_SESSION,
            TouchMediaLoadState.Decision.REPLACE_DOCUMENT_SESSION -> {
                reopenAndLoad(url)
            }""",
        """            TouchMediaLoadState.Decision.RECOVER_RETAINED_SESSION -> {
                reopenAndLoad(url)
            }
            TouchMediaLoadState.Decision.REPLACE_DOCUMENT_SESSION -> {
                loadConfiguredUrl(url)
            }""",
        {"crossUrlProductionPathCoversThenRetiresThePreviousSession"},
    )
    media_active_crash_red = mutate(
        "media-active-crash-retry-red", "media",
        "} else {\n                Decision.RETRY_REQUIRED\n            }",
        "} else {\n                Decision.LOAD\n            }",
        {
            "contentCrashRequiresExplicitRetryAndUsesTheMostRecentConfiguredUrl",
            "delayedKillAfterWarmReturnRecoversOnceWithoutPaintRearming",
            "activeCrashAndExplicitRetryClearTheReturnTicket",
            "hiddenDeliveredClosureConsumesAllRecoveryBeforeReplacement",
            "hiddenContentCrashRecoversExactlyOnceWhenTheUserReturns",
        },
    )
    media_render_deadline_red = mutate(
        "media-foreground-render-deadline-red", "media",
        "armRenderDeadline(renderDeadline.beginLoading(load, active))",
        "armRenderDeadline(null)",
        {"productionViewArmsAndFencesTheForegroundRenderDeadline"},
    )
    media_deadline_close_red = mutate(
        "media-render-deadline-close-red", "media",
        "renderDeadline.close()\n        cancelRenderDeadlineTask()",
        "// render deadline teardown disabled",
        {"productionViewArmsAndFencesTheForegroundRenderDeadline"},
    )
    camera_palette_red = mutate(
        "camera-palette-contrast-red", "camera_palette",
        "accent = 0xFF4F9F9A.toInt(), accentInk = 0xFF101722.toInt(),\n                disabledSurface = 0xFFD7D5CE.toInt(),",
        "accent = 0xFF4F9F9A.toInt(), accentInk = 0xFFF1EDE4.toInt(),\n                disabledSurface = 0xFFD7D5CE.toInt(),",
        {"camera controls keep readable foregrounds on each explicit palette"},
    )
    camera_label_red = mutate(
        "camera-label-prefix-red", "camera_label",
        """    private fun collapseImmediatelyRepeatedLeadingTokens(tokens: List<String>): List<String> {
        val repeatedPrefixLength = (1..tokens.size / 2).firstOrNull { prefixLength ->
            tokens.take(prefixLength) == tokens.drop(prefixLength).take(prefixLength)
        } ?: return tokens
        return tokens.take(repeatedPrefixLength) + tokens.drop(repeatedPrefixLength * 2)
    }""",
        """    private fun collapseImmediatelyRepeatedLeadingTokens(tokens: List<String>): List<String> {
        return tokens
    }""",
        {"cameraLabelsCollapseOnlyImmediatelyRepeatedLeadingTokens"},
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
    birds_url_validation_red = mutate(
        "birds-url-validation-red", "configuration",
        "if (birds != null && !urlPolicy.isSafeConfigurationUrl(birds)) return null",
        "if (false) return null",
        {"optional Art and Birds feeds preserve HTTP and HTTPS policy and reject unsafe URLs"},
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
            "pause between registration and enqueue leaves no canceled wrapper",
        },
    )
    canceled_queue_red = mutate(
        "canceled-queue-removal-red", "scope",
        "if (task.isCancelled && executor is ThreadPoolExecutor) executor.remove(task)",
        "if (task.isCancelled && executor is ThreadPoolExecutor) Unit",
        {
            "calendar replacement burst retains only latest pending task",
            "pause between registration and enqueue leaves no canceled wrapper",
        },
    )
    enqueue_cancel_red = mutate(
        "cancel-before-enqueue-red", "scope",
        "executor.execute(task)\n            // Pause may cancel after registration but before execute enqueues the task.\n            discardCanceled(executor, task)",
        "executor.execute(task)\n            // Negative proof: omit the post-enqueue cancellation check.",
        {"pause between registration and enqueue leaves no canceled wrapper"},
    )
    activity_queue_red = mutate_activity_queue()
    calendar_range_end_red = mutate(
        "calendar-range-end-red", "calendar_ranges",
        "val exclusiveEnd = allDay || endZoned?.toLocalTime() == java.time.LocalTime.MIDNIGHT",
        "val exclusiveEnd = false",
        {"all day exclusive end does not select following day", "honorsExclusiveEndForMultiDayAllDayEvents", "midnight timed end is exclusive of next day"},
    )
    calendar_parser_cap_red = mutate(
        "calendar-range-cap-red", "calendar_parser",
        "const val MAX_EVENTS_PER_CALENDAR = 200",
        "const val MAX_EVENTS_PER_CALENDAR = 201",
        {"parser caps each calendar before rendering"},
    )
    calendar_closed_red = mutate_calendar_closed(
        "calendar-closed-repository-red",
        {"closed repository rejects a range before reading a token"},
    )
    calendar_stale_socket_red = mutate(
        "calendar-stale-socket-cleanup-red", "calendar_repository",
        "invalidated.futures.toList()",
        "invalidated.futures.toList().also { invalidated.futures.clear() }",
        {"invalidate retains stale request handles until serialized cleanup"},
    )
    calendar_presentation_red = mutate(
        "calendar-presentation-legibility-red", "calendar_presentation",
        "const val CONTROL_TEXT_SP = 22f",
        "const val CONTROL_TEXT_SP = 14f",
        {"calendar controls retain readable text and unclipped weekday space"},
    )
    restored = run("restored-green", set())
    bridge_restored = run_bridge("prepared-bridge-restored-green", set())
    unchanged_tests = {str(test.relative_to(root)): digest(test) for _, (_, test, _) in suites.items()}
    if unchanged_tests != test_hashes or digest(bridge_test) != bridge_test_hash:
        raise RuntimeError("proof changed test bytes")
    (out / "touchframe-source-proof.json").write_text(json.dumps({
        "proof": "touchframe-native-direct-data-v3", "passed": True, "command": command,
        "packagingInitial": packaging_initial, "packagingVersionRed": packaging_red,
        "packagingRestored": packaging_restored, "packagingUnchangedSha256": packaging_hashes,
        "sourceTestAnnotationCount": expected_tests, "initial": initial, "preparedBridgeInitial": bridge_initial,
        "preparedBridgeSessionGateRed": bridge_red, "edgeRed": edge_red,
        "edgeStreamOwnershipRed": edge_stream_red, "edgeStreamOnceRed": edge_once_red,
        "mediaKillBindingRed": kill_binding_red, "mediaKillOnceRed": kill_once_red,
        "mediaKillGenerationRed": kill_generation_red,
        "calendarWeekPreviewRed": week_preview_red, "calendarWeekDetailsRed": week_details_red,
        "calendarWeekDefaultRed": week_default_red, "weatherLocationProvisionRed": weather_location_red,
        "homeLayoutTransitionRed": home_first_measure_red,
        "shellContrastRed": shell_contrast_red,
        "railScrollableViewportRed": rail_viewport_red,
        "settingsReturnOverlayRed": settings_return_red,
        "calendarCapRed": cap_red, "cancelGenerationRed": cancel_red,
        "climateStaleGateRed": climate_stale_red,
        "mediaHiddenReturnRecoveryRed": media_red,
        "mediaContentfulPaintReadinessRed": media_paint_readiness_red,
        "mediaCrossUrlSessionRed": media_cross_url_session_red,
        "mediaActiveCrashRetryRed": media_active_crash_red,
        "mediaForegroundRenderDeadlineRed": media_render_deadline_red,
        "mediaRenderDeadlineCloseRed": media_deadline_close_red,
        "cameraPaletteContrastRed": camera_palette_red,
        "cameraLabelPrefixRed": camera_label_red,
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
        "birdsUrlValidationRed": birds_url_validation_red,
        "settingsMonotonicRevisionRed": settings_revision_red,
        "provisioningCalendarCapRed": provisioning_cap_red,
        "pausedTaskEntryRed": scope_red,
        "canceledQueueRemovalRed": canceled_queue_red,
        "cancelBeforeEnqueueRed": enqueue_cancel_red,
        "activityCancelQueueRed": activity_queue_red,
        "calendarExclusiveRangeEndRed": calendar_range_end_red,
        "calendarRangeParserCapRed": calendar_parser_cap_red,
        "calendarClosedRepositoryRed": calendar_closed_red,
        "calendarStaleSocketCleanupRed": calendar_stale_socket_red,
        "calendarPresentationLegibilityRed": calendar_presentation_red,
        "restored": restored, "preparedBridgeRestored": bridge_restored,
        "preparedBridgeSourceSha256": bridge_source_hash,
        "preparedBridgeRestoredSourceSha256": digest(bridge_source),
        "preparedBridgeTestSha256": bridge_test_hash,
        "originalSourceSha256": source_hashes,
        "restoredSourceSha256": {name: digest(source) for name, (source, _, _) in suites.items()},
        "unchangedTestSha256": test_hashes, "staticContract": static,
    }, indent=2, sort_keys=True) + "\n")
finally:
    packaging_manifest.write_bytes(packaging_bytes)
    for name, (source, _, _) in suites.items():
        source.write_bytes(source_bytes[name])
    if "bridge_source_bytes" in globals():
        bridge_source.write_bytes(bridge_source_bytes)
