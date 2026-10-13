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
suites = {
    "shell": (module / "src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayoutTest.kt", "com.wyattfleming.touchframe.ui.TouchShellLayoutTest"),
    "parser": (module / "src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeParser.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeParserTest.kt", "com.wyattfleming.touchframe.home.TouchHomeParserTest"),
    "repository": (module / "src/main/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepository.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/home/TouchHomeRepositoryTest.kt", "com.wyattfleming.touchframe.home.TouchHomeRepositoryTest"),
    "media": (module / "src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/media/TouchMediaLoadStateTest.kt", "com.wyattfleming.touchframe.media.TouchMediaLoadStateTest"),
    "companion": (module / "src/main/kotlin/com/wyattfleming/touchframe/control/TouchCompanionController.kt", module / "src/test/kotlin/com/wyattfleming/touchframe/control/TouchCompanionControllerTest.kt", "com.wyattfleming.touchframe.control.TouchCompanionControllerTest"),
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
    companion = suites["companion"][0].read_text()
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
        "worker credential reread": "if (credentials() != currentCredentials)",
        "worker event acceptance": "onEvent(it, expectedGeneration) { isCurrent(expectedGeneration) }",
        "main callback timeout cancellation": "task.cancel(false)",
        "dispatch-time command expiry": "if (nowMillis() > command.expiresAtMillis)",
    }
    combined = shell + repo + parser + media + companion + scope
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
static = static_contract()
source_bytes = {name: source.read_bytes() for name, (source, _, _) in suites.items()}
source_hashes = {name: hashlib.sha256(value).hexdigest() for name, value in source_bytes.items()}
test_hashes = {str(test.relative_to(root)): digest(test) for _, (_, test, _) in suites.items()}
expected_tests = sum(test_count(test) for _, (_, test, _) in suites.items())
command = [
    "./gradlew", "--no-daemon", "--max-workers=2", ":touchframe:testDebugUnitTest",
    *[arg for _, (_, _, klass) in suites.items() for arg in ("--tests", klass)],
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


try:
    initial = run("initial-green", set())
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
        {"content crash requires explicit retry and uses the most recent configured url"},
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
        "Unit",
        {"timed out queued callback is cancelled before a delayed main executor runs it"},
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
    unchanged_tests = {str(test.relative_to(root)): digest(test) for _, (_, test, _) in suites.items()}
    if unchanged_tests != test_hashes:
        raise RuntimeError("proof changed test bytes")
    (out / "touchframe-source-proof.json").write_text(json.dumps({
        "proof": "touchframe-native-direct-data-v2", "passed": True, "command": command,
        "sourceTestAnnotationCount": expected_tests, "initial": initial, "edgeRed": edge_red,
        "calendarCapRed": cap_red, "cancelGenerationRed": cancel_red, "mediaCrashRetryRed": media_red,
        "credentialRereadRed": credential_red, "eventWorkerRed": event_worker_red,
        "dispatchExpiryRed": expiry_red, "delayedMainCancellationRed": timeout_cancel_red,
        "pausedTaskEntryRed": scope_red,
        "restored": restored,
        "originalSourceSha256": source_hashes,
        "restoredSourceSha256": {name: digest(source) for name, (source, _, _) in suites.items()},
        "unchangedTestSha256": test_hashes, "staticContract": static,
    }, indent=2, sort_keys=True) + "\n")
finally:
    for name, (source, _, _) in suites.items():
        source.write_bytes(source_bytes[name])
