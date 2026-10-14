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
    "day": ("com.wyattfleming.touchframe.calendar.TouchCalendarDayStyleTest", tests / "calendar/TouchCalendarDayStyleTest.kt"),
    "today": ("com.wyattfleming.touchframe.calendar.TouchCalendarTodayColumnTest", tests / "calendar/TouchCalendarTodayColumnTest.kt"),
    "chip": ("com.wyattfleming.touchframe.ui.TouchShellChipOrderTest", tests / "ui/TouchShellChipOrderTest.kt"),
    "month": ("com.wyattfleming.touchframe.calendar.TouchCalendarMonthLayoutTest", tests / "calendar/TouchCalendarMonthLayoutTest.kt"),
    "icons": ("com.wyattfleming.touchframe.ui.TouchIconPathTest", tests / "ui/TouchIconPathTest.kt"),
    "segment": ("com.wyattfleming.touchframe.ui.TouchSegmentedWidthTest", tests / "ui/TouchSegmentedWidthTest.kt"),
    "destinations": ("com.wyattfleming.touchframe.ui.TouchDestinationOrderTest", tests / "ui/TouchDestinationOrderTest.kt"),
    "ocean": ("com.wyattfleming.touchframe.ocean.TouchOceanPresentationTest", tests / "ocean/TouchOceanPresentationTest.kt"),
    "ocean-scene": ("com.wyattfleming.touchframe.ocean.TouchOceanSceneSourceTest", tests / "ocean/TouchOceanSceneSourceTest.kt"),
    "ocean-view": ("com.wyattfleming.touchframe.ocean.TouchOceanViewSourceTest", tests / "ocean/TouchOceanViewSourceTest.kt"),
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
    ("month-past-day-tone-red", main / "calendar/TouchCalendarModel.kt",
     "date.isBefore(today) -> TouchCalendarDayTone.PAST", "false -> TouchCalendarDayTone.PAST", "day",
     {"month cells separate past days, today and upcoming days", "days outside the month are past when earlier and quiet placeholders when later"}),
    ("today-finished-events-red", main / "calendar/TouchCalendarWeekPresentation.kt",
     "(event.endEpochMillis ?: event.startEpochMillis) <= nowMillis -> EventTiming.ENDED", "false -> EventTiming.ENDED", "today",
     {"finished events collapse into a count and the current one reads Now",
      "timing treats all-day events as current all day and open-ended ones as finished once started"}),
    ("tab-chip-padding-order-red", main / "ui/TouchShellLayout.kt",
     """        background = insetChip(rounded(palette.settings, palette.settingsStroke), 0)
        setPadding(dp(20), 0, dp(20), 0)""",
     """        setPadding(dp(20), 0, dp(20), 0)
        background = insetChip(rounded(palette.settings, palette.settingsStroke), 0)""", "chip",
     {"tab chips are assigned before their padding so the inset background cannot erase it"}),
    ("month-six-week-fit-red", main / "calendar/TouchCalendarView.kt",
     "val fitted = (viewportHeightDp - (if (wall) WALL_CHROME_DP else PANEL_CHROME_DP)) / weeks - ROW_GAP_DP",
     "val fitted = (viewportHeightDp - (if (wall) WALL_CHROME_DP else PANEL_CHROME_DP)) / 5 - ROW_GAP_DP", "month",
     {"six-week months shrink their cells to fit the frame"}),
    ("icon-implicit-moveto-red", module / "src/main/res/drawable/ic_touch_chevron_left.xml",
     'android:pathData="m15 5l-7 7 7 7"', 'android:pathData="m15 5-7 7 7 7"', "icons",
     {"icon paths never rely on implicit lineto after moveto, which API 23 draws as nothing"}),
    ("segment-equal-weight-red", main / "ui/TouchTheme.kt",
     "LinearLayout.LayoutParams(segmentWidth, dp(scale.target - 8), 1f)",
     "LinearLayout.LayoutParams(0, dp(scale.target - 8), 1f)", "segment",
     {"every segment starts at the widest label so a wrap-content control never truncates one"}),
    ("ocean-scene-redraw-red", main / "ocean/TouchOceanScene.kt",
     """        fishTail.rotation = sin(2 * PI * seconds * 1000 / TAIL_PERIOD_MILLIS).toFloat() * TAIL_DEGREES
    }""",
     """        fishTail.rotation = sin(2 * PI * seconds * 1000 / TAIL_PERIOD_MILLIS).toFloat() * TAIL_DEGREES
        invalidate()
    }""", "ocean-scene",
     {"each frame only moves views and never redraws the swell, sky or fish"}),
    ("ocean-remote-order-red", main / "ui/TouchDestination.kt",
     "        if (TouchDestination.OCEAN in order) return order\n        val weather",
     "        if (true) return order\n        val weather", "destinations",
     {"Ocean stays right after Weather when a remote order does not name it"}),
    ("ocean-chart-gauge-red", main / "ocean/TouchOceanPresentation.kt",
     "            minM = lowest - padding,\n            maxM = highest + padding,",
     "            minM = minOf(0.0, lowest),\n            maxM = maxOf(1.8288, highest + padding),", "ocean",
     {"the chart is fitted to its own window so a day's swing fills it"}),
    ("ocean-label-clipped-red", main / "ocean/TouchOceanView.kt",
     'addView(kit.text("Water temperature", scale.headline, TouchFonts.Face.TEXT_MEDIUM, ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP))',
     'addView(kit.text("Water temperature", scale.headline, TouchFonts.Face.TEXT_MEDIUM, ink, maxLines = 1))', "ocean-view",
     {"every text declares its own layout params so a wrap-content column cannot clip it"}),
    ("ocean-chart-fixed-height-red", main / "ocean/TouchOceanView.kt",
     "            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(8) })",
     "            }, LinearLayout.LayoutParams(MATCH, kit.dp(250)).apply { topMargin = kit.dp(8) })", "ocean-view",
     {"the tide section is stacked under the conditions and its chart takes the height that is left"}),
    ("ocean-degree-baseline-red", main / "ocean/TouchOceanView.kt",
     "isBaselineAligned = false", "isBaselineAligned = true", "ocean-view",
     {"the degree mark hangs from the top of the numeral instead of sitting on its baseline"}),
    ("ocean-streak-speeds-red", main / "ocean/TouchOceanPresentation.kt",
     "(((index * spacing + direction * seconds * speed) % span) + span) % span",
     "(((index * spacing + direction * seconds * speed * (0.7 + 0.2 * (index % 3))) % span) + span) % span", "ocean",
     {"wind streaks keep their spacing so neighbouring rows never stack into a menu-like glyph"}),
    ("ocean-overlay-uncached-red", main / "ocean/TouchOceanView.kt",
     "overlay.setLayerType(if (value) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, null)",
     "overlay.setLayerType(LAYER_TYPE_NONE, null)", "ocean-view",
     {"while the scene moves the text and chart above it are one cached layer released when Ocean is hidden"}),
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
