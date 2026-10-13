#!/usr/bin/env python3
"""Source-only red / exact-restore green proofs for the FrameOS Touch redesign fixes.

Each mutation reverts one fix in production source, runs only that fix's guarding test class,
requires exactly the named cases to fail with assertion failures, restores the exact bytes,
and finally runs every guarding class green. Tests are never edited by this script.
"""
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
main = module / "src/main/kotlin/com/wyattfleming/touchframe"
tests = module / "src/test/kotlin/com/wyattfleming/touchframe"
out = root / "reports" / "touchframe-design"
out.mkdir(parents=True, exist_ok=True)
result_dir = module / "build" / "test-results" / "testDebugUnitTest"

CLASSES = {
    "agenda": ("com.wyattfleming.touchframe.home.TouchHomeAgendaTest", tests / "home/TouchHomeAgendaTest.kt"),
    "camera": ("com.wyattfleming.touchframe.media.TouchCameraPolicyTest", tests / "media/TouchCameraPolicyTest.kt"),
    "idle": ("com.wyattfleming.touchframe.ui.TouchIdleReturnPolicyTest", tests / "ui/TouchIdleReturnPolicyTest.kt"),
    "week": ("com.wyattfleming.touchframe.calendar.TouchCalendarWeekPresentationTest", tests / "calendar/TouchCalendarWeekPresentationTest.kt"),
    "theme": ("com.wyattfleming.touchframe.ui.TouchThemeTest", tests / "ui/TouchThemeTest.kt"),
}

MUTATIONS = [
    ("agenda-overflow-reserve-red", main / "home/TouchHomeAgenda.kt",
     "val reserve = if (shown + rows.size + 1 < ordered.size) 1 else 0", "val reserve = 0", "agenda",
     {"events that do not fit are counted, never silently dropped", "the overflow line is only reserved while events remain"}),
    ("climate-draft-confirm-red", main / "home/TouchHomeAgenda.kt",
     "if (confirmed) reset(climate?.targetTemperature)", "if (false) reset(climate?.targetTemperature)", "agenda",
     {"a confirmed setpoint clears the draft so later refreshes show the real value"}),
    ("weather-label-red", main / "home/TouchHomeAgenda.kt",
     "if (known != WeatherCondition.UNKNOWN) return known.displayName", "if (false) return known.displayName", "agenda",
     {"weather glance uses human condition names and never a raw machine state"}),
    ("camera-periodic-preempt-red", main / "media/TouchCameraView.kt",
     "if (closed || !active || inFlight) return false", "if (closed || !active || false) return false", "camera",
     {"periodicTickNeverInvalidatesAFetchInFlight"}),
    ("idle-network-prompt-red", main / "ui/TouchDestination.kt",
     "!authorizationOpen && configured && networkAllowed && !destination.isImmersiveMedia",
     "!authorizationOpen && configured && !destination.isImmersiveMedia", "idle",
     {"media, sign-in, missing configuration and denied local network never auto-navigate"}),
    ("week-fitted-page-red", main / "calendar/TouchCalendarWeekPresentation.kt",
     "val size = pageSize.coerceIn(1, DETAILS_PER_PAGE)", "val size = DETAILS_PER_PAGE", "week",
     {"fittedDetailPagesShowOnlyWholeRowsAndKeepEveryEventReachable"}),
    ("theme-control-stroke-red", main / "ui/TouchTheme.kt",
     "stroke = 0xFF827C70.toInt()", "stroke = 0xFFCDC8BD.toInt()", "theme",
     {"control boundaries and icons meet the 3 to 1 non-text minimum"}),
    ("theme-night-selection-red", main / "ui/TouchTheme.kt",
     "railSelected = 0xFF2A2318.toInt()", "railSelected = 0xFFE8C88E.toInt()", "theme",
     {"every text token pair meets AA in both palettes", "night palette is dim and warm for bedrooms"}),
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label: str, keys: list[str], expected_failures: set[str]) -> dict:
    if result_dir.exists():
        shutil.rmtree(result_dir)
    command = ["./gradlew", "--no-daemon", "--max-workers=2", ":touchframe:testDebugUnitTest", "--rerun"]
    for key in keys:
        command += ["--tests", CLASSES[key][0]]
    log = out / f"{label}.log"
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    cases = []
    for key in keys:
        xmls = list(result_dir.rglob(f"TEST-{CLASSES[key][0]}.xml"))
        if len(xmls) != 1:
            tail = "\n".join(log.read_text(errors="replace").splitlines()[-60:])
            raise RuntimeError(f"{label}: expected one JUnit XML for {CLASSES[key][0]}, found {xmls}; exit={result.returncode}\n{tail}")
        shutil.copy2(xmls[0], out / f"{label}-{key}.xml")
        cases.extend(ET.parse(xmls[0]).getroot().iter("testcase"))
    failed = {case.get("name") or "" for case in cases if case.find("failure") is not None}
    errors = [case for case in cases if case.find("error") is not None]
    skipped = [case for case in cases if case.find("skipped") is not None]
    for case in cases:
        failure = case.find("failure")
        if failure is not None and failure.get("type") not in {"java.lang.AssertionError", "org.junit.ComparisonFailure"}:
            raise RuntimeError(f"{label}: {case.get('name')} failed with {failure.get('type')}, not an assertion")
    if failed != expected_failures or errors or skipped:
        raise RuntimeError(f"{label}: failed={sorted(failed)} expected={sorted(expected_failures)} errors={len(errors)} skipped={len(skipped)}")
    if (result.returncode == 0) != (not expected_failures):
        raise RuntimeError(f"{label}: unexpected Gradle exit {result.returncode}")
    return {"exit": result.returncode, "tests": len(cases), "failedCases": sorted(failed), "log": log.name, "logSha256": digest(log)}


test_hashes = {key: digest(path) for key, (_, path) in CLASSES.items()}
sources = sorted({path for _, path, *_ in MUTATIONS})
source_hashes = {str(path.relative_to(root)): digest(path) for path in sources}
for label, path, before, after, _, _ in MUTATIONS:
    text = path.read_text(encoding="utf-8")
    if text.count(before) != 1 or after in text:
        raise RuntimeError(f"{label}: target must occur exactly once and its replacement must be absent")

results = {}
for label, path, before, after, key, expected in MUTATIONS:
    original = path.read_bytes()
    try:
        path.write_text(original.decode("utf-8").replace(before, after), encoding="utf-8")
        results[label] = run(label, [key], expected)
    finally:
        path.write_bytes(original)
    if digest(path) != source_hashes[str(path.relative_to(root))]:
        raise RuntimeError(f"{label}: source was not restored exactly")

green = run("design-restored-green", list(CLASSES), set())
if {key: digest(path) for key, (_, path) in CLASSES.items()} != test_hashes:
    raise RuntimeError("a guarding test changed during the proof")
if {str(path.relative_to(root)): digest(path) for path in sources} != source_hashes:
    raise RuntimeError("production source changed during the proof")
(out / "touchframe-design-source-proof.json").write_text(json.dumps({
    "passed": True,
    "sourceSha256": source_hashes,
    "unchangedTestSha256": test_hashes,
    "negative": results,
    "restoredGreen": green,
}, indent=2) + "\n")
print(f"TouchFrame design source-only red ({len(results)}) / exact restore green verified")
