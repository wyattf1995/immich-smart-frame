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
shared_tests = frame / "app/src/test/kotlin/com/wyattfleming/frameos"
shared_main = frame / "app/src/main/kotlin/com/wyattfleming/frameos"
# Classes guarding shared FrameOS sources run in :frame-shared, everything else in :touchframe.
TASKS = {
    "touchframe": (":touchframe:testDebugUnitTest", result_dir),
    "frame-shared": (":frame-shared:testDebugUnitTest", frame / "frame-shared" / "build" / "test-results" / "testDebugUnitTest"),
}

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
    "manifest": ("com.wyattfleming.touchframe.TouchFrameActivityManifestTest", tests / "TouchFrameActivityManifestTest.kt"),
    "todo": ("com.wyattfleming.touchframe.home.TouchTodoTest", tests / "home/TouchTodoTest.kt"),
    "todo-repo": ("com.wyattfleming.touchframe.home.TouchTodoRepositoryTest", tests / "home/TouchTodoRepositoryTest.kt"),
    "home-todo": ("com.wyattfleming.touchframe.ui.TouchHomeTodoSourceTest", tests / "ui/TouchHomeTodoSourceTest.kt"),
    "ocean-glance": ("com.wyattfleming.touchframe.ocean.TouchOceanGlanceTest", tests / "ocean/TouchOceanGlanceTest.kt"),
    "power-parser": ("com.wyattfleming.touchframe.power.TouchPowerParserTest", tests / "power/TouchPowerParserTest.kt"),
    "power-signals": ("com.wyattfleming.touchframe.power.TouchPowerSignalsTest", tests / "power/TouchPowerParserTest.kt"),
    "power": ("com.wyattfleming.touchframe.power.TouchPowerPresentationTest", tests / "power/TouchPowerPresentationTest.kt"),
    "power-repo": ("com.wyattfleming.touchframe.power.TouchPowerRepositoryTest", tests / "power/TouchPowerRepositoryTest.kt"),
    "power-view": ("com.wyattfleming.touchframe.power.TouchPowerViewSourceTest", tests / "power/TouchPowerViewSourceTest.kt"),
    "motion": ("com.wyattfleming.touchframe.ui.TouchMotionTest", tests / "ui/TouchMotionTest.kt"),
    "media-tap": ("com.wyattfleming.touchframe.ui.TouchMediaTapTest", tests / "ui/TouchMediaTapTest.kt"),
    "disclosure": ("com.wyattfleming.touchframe.ui.TouchDisclosureTest", tests / "ui/TouchDisclosureTest.kt"),
    "weather": ("com.wyattfleming.touchframe.weather.TouchWeatherPresentationTest", tests / "weather/TouchWeatherPresentationTest.kt"),
    "weather-colors": ("com.wyattfleming.touchframe.weather.TouchWeatherColorsTest", tests / "weather/TouchWeatherColorsTest.kt"),
    "weather-sun": ("com.wyattfleming.touchframe.weather.TouchSunRepositoryTest", tests / "weather/TouchSunRepositoryTest.kt"),
    "weather-view": ("com.wyattfleming.touchframe.weather.TouchWeatherViewSourceTest", tests / "weather/TouchWeatherViewSourceTest.kt"),
    "weather-parser": ("com.wyattfleming.frameos.weather.HomeAssistantWeatherParserTest", shared_tests / "weather/HomeAssistantWeatherParserTest.kt", "frame-shared"),
    "weather-codec": ("com.wyattfleming.frameos.weather.WeatherSnapshotCodecTest", shared_tests / "weather/WeatherSnapshotCodecTest.kt", "frame-shared"),
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
    ("activity-restart-in-place-red", module / "src/main/AndroidManifest.xml",
     'android:stateNotNeeded="true"', 'android:stateNotNeeded="false"', "manifest",
     {"Android restarts Touch in place when its process is killed on screen"}),
    ("todo-overdue-red", main / "home/TouchTodo.kt",
     'date.isBefore(today) -> Due("Overdue", Tone.LATE)', 'false -> Due("Overdue", Tone.LATE)', "todo",
     {"overdue items are labelled Overdue and today's items Today"}),
    ("todo-groceries-exclusive-red", main / "home/TouchTodo.kt",
     "it.entityId != grocery?.entityId", 'it.entityId != "todo.none"', "todo",
     {"a list chosen as groceries is never also a task list"}),
    ("todo-preview-count-red", main / "home/TouchTodo.kt",
     '" and ${items.size - names} more"', '" and more"', "todo",
     {"the groceries line names the first items and counts the rest"}),
    ("todo-missing-list-red", main / "home/TouchTodoRepository.kt",
     "response.statusCode == 400 -> TouchTodoLoad(", "response.statusCode == 499 -> TouchTodoLoad(", "todo-repo",
     {"refresh failures map to sign-in, missing list or offline and never throw"}),
    ("todo-undo-delay-red", main / "ui/TouchHomeView.kt",
     "postDelayed(send, UNDO_MILLIS)", "post(send)", "home-todo",
     {"a checked item is sent to Home Assistant only after the Undo delay"}),
    ("photo-rgb565-red", main / "media/TouchPhotoOfDay.kt",
     "inPreferredConfig = Bitmap.Config.RGB_565", "inPreferredConfig = Bitmap.Config.ARGB_8888", "home-todo",
     {"the photo of the day is sampled to the panel and stored at two bytes a pixel"}),
    ("ocean-glance-next-tide-red", main / "ocean/TouchOceanGlance.kt",
     "it.epochMillis > nowMillis", "it.epochMillis > 0L", "ocean-glance",
     {"Home's ocean glance names the spot, its water and the next tide",
      "with every listed tide in the past the glance says nothing about tides"}),
    ("power-meter-reset-red", main / "power/TouchPower.kt",
     "total += if (end >= start) end - start else end", "total += end - start", "power-signals",
     {"a meter that went backwards was reset, so its new reading is the hour's use"}),
    ("power-laundry-restart-red", main / "power/TouchPower.kt",
     'if (rows[i - 1].state == "on" && rows[i].state == "off")', 'if (rows[i].state == "off")', "power-signals",
     {"a Home Assistant restart's unknown-to-off is not a finished load"}),
    ("power-solar-restart-red", main / "power/TouchPower.kt",
     "if (rows[i].number != value) break", "if (true) break", "power-signals",
     {"a restart that rewrites the same reading does not count as a Sunrun report"}),
    ("power-unpriced-schedule-red", main / "power/TouchPower.kt",
     "if (today.any { it !in rates } || tomorrow.any { it !in ratesTomorrow }) return null", "if (false) return null", "power-parser",
     {"a schedule that names a period without a price is not shown at all"}),
    ("power-next-change-red", main / "power/TouchPowerPresentation.kt",
     "if (at.period != period) return t", "if (at.period == period) return t", "power",
     {"the headline names the period now and when it ends, then the next period and its price",
      "late at night the headline leaves tomorrow to the next line so it fits on one line"}),
    ("power-ring-clip-red", main / "power/TouchPowerPresentation.kt",
     "maxOf(RING_STEP_KWH, ceil((kwh.maxOrNull() ?: 0.0) / RING_STEP_KWH) * RING_STEP_KWH)", "maxOf(RING_STEP_KWH, 0.0 * kwh.size)", "power",
     {"the use ring grows past half a kWh instead of clipping a busy hour"}),
    ("power-history-optional-red", main / "power/TouchPowerRepository.kt",
     "optional { fillHourReadings(setup, starts, headers, deadline) }", "run { fillHourReadings(setup, starts, headers, deadline) }", "power-repo",
     {"a failed history read keeps the live readings"}),
    ("power-view-minute-red", main / "power/TouchPowerView.kt",
     "const val SLOT_MILLIS = 60_000L", "const val SLOT_MILLIS = 1_000L", "power-view",
     {"the page never animates and the dial turns only with the minute"}),
    ("destinations-power-anchor-red", main / "ui/TouchDestination.kt",
     "else order.subList(0, anchor + 1) + TouchDestination.POWER + order.subList(anchor + 1, order.size)", "else order + TouchDestination.POWER", "destinations",
     {"Power follows Ocean, or Weather without Ocean, when a remote order does not name it"}),
    ("shell-stacked-tabs-red", main / "ui/TouchShellLayout.kt",
     "fun stackedTabs(large: Boolean): Boolean = !large", "fun stackedTabs(large: Boolean): Boolean = large", "destinations",
     {"the 1280 panel stacks each tab's label under its icon and the 1920 bar keeps them side by side"}),
    ("motion-weather-still-red", frame / "app/src/main/kotlin/com/wyattfleming/frameos/weather/WeatherRenderCadence.kt",
     "if (sceneAnimated && motionEnabled) return ANIMATED_FRAME_DELAY_MILLIS", "if (sceneAnimated) return ANIMATED_FRAME_DELAY_MILLIS", "motion",
     {"with Android animations off an animated weather scene holds still and wakes only for the hourly page"}),
    ("motion-menu-exit-red", main / "ui/TouchShellLayout.kt",
     "bar.animate().translationX(-width.toFloat())", "bar.animate().translationX(-0f)", "motion",
     {"the immersive menu leaves the way it came in"}),
    ("media-tap-still-red", main / "ui/TouchShellLayout.kt",
     "abs(x - downX) <= slop && abs(y - downY) <= slop", "abs(x - downX) >= 0f && abs(y - downY) >= 0f", "media-tap",
     {"swipes, long presses, native screens and an open menu are not taps on the photo"}),
    ("edge-hint-interval-red", main / "ui/TouchShellLayout.kt",
     "nowMillis - lastShownMillis >= TAP_HINT_INTERVAL_MILLIS", "nowMillis - lastShownMillis >= 0L", "media-tap",
     {"the edge hint shows on a tap at most once every ten minutes"}),
    ("power-solar-end-red", main / "power/TouchPowerRepository.kt",
     "history(nowMillis - SOLAR_WINDOW_MILLIS, nowMillis,", "history(nowMillis - SOLAR_WINDOW_MILLIS, null,", "power-repo",
     {"the solar and laundry reads end now, because Home Assistant stops an open read one day after its start"}),
    ("power-headline-day-red", main / "power/TouchPowerPresentation.kt",
     "else -> \"${price.period.label} until ${hourTime(firstChange)}\"", "else -> \"${price.period.label} until ${whenText(firstChange)}\"", "power",
     {"late at night the headline leaves tomorrow to the next line so it fits on one line"}),
    ("edge-tap-opens-menu-red", main / "ui/TouchShellLayout.kt",
     "if (downX <= edgeWidthPx) TapOutcome.OPEN_MENU", "if (downX < 0f) TapOutcome.OPEN_MENU", "disclosure",
     {"a tap that starts on the edge handle opens the menu and a tap on the photo shows the hint"}),
    ("sheet-pane-title-red", main / "ui/TouchHomeView.kt",
     "layer.accessibilityPaneTitle = sheetTitle?.text?.takeIf { sheet != Sheet.NONE }", "layer.accessibilityPaneTitle = null", "disclosure",
     {"opening a Home sheet tells TalkBack which sheet opened without moving its focus"}),
    ("weather-tap-pages-red", frame / "app/src/main/kotlin/com/wyattfleming/frameos/weather/WeatherTapAction.kt",
     "pagesOnTap && pageCount > 1 -> NEXT_PAGE", "false -> NEXT_PAGE", "disclosure",
     {"a tap on Weather signs in when that is needed, otherwise turns the hourly page"}),
    ("companion-show-page-red", main / "ui/TouchDestination.kt",
     "\"power\" -> TouchDestination.POWER", "\"power\" -> null", "disclosure",
     {"the companion can open Ocean and Power by name and still only known pages"}),
    ("weather-min-range-red", main / "weather/TouchWeatherPresentation.kt",
     "val range = maxOf((high - low).toDouble(), minRange)", "val range = maxOf((high - low).toDouble(), 1.0)", "weather",
     {"a calm day keeps at least 12 degrees of height, centred on its readings"}),
    ("weather-turn-red", main / "weather/TouchWeatherPresentation.kt",
     "if ((hi > lo && lo != 0) || hi == 0) \"Low\" to lo else \"High\" to hi", "if (false) \"Low\" to lo else \"High\" to hi", "weather",
     {"the sentence names the next turn - the afternoon high before tonight's low, the overnight low before tomorrow's high"}),
    ("weather-rain-amount-red", main / "weather/TouchWeatherPresentation.kt",
     "            if (amount <= 0.0) {", "            if (amount <= 9.0) {", "weather",
     {"met no rain amounts become one bar per wet stretch labelled with its total, and a sentence saying when it ends",
      "a little rain is called light rain and millimetres keep their unit",
      "while it rains now the Now column joins the run without counting the next hour twice"}),
    ("weather-now-double-count-red", main / "weather/TouchWeatherPresentation.kt",
     "if (hour.counted) total += amount", "total = total + amount", "weather",
     {"while it rains now the Now column joins the run without counting the next hour twice"}),
    ("weather-chance-red", main / "weather/TouchWeatherPresentation.kt",
     "val first = wet.firstOrNull { (it.percent ?: 0) >= SAY_RAIN_PERCENT } ?: return null",
     "val first = wet.firstOrNull { (it.percent ?: 0) >= BAR_RAIN_PERCENT } ?: return null", "weather",
     {"a chance of rain names the first hour at 30 percent and the highest chance after it"}),
    ("weather-night-sun-red", main / "weather/TouchWeatherPresentation.kt",
     "val rising = sun?.nextRisingMillis\n        val setting = sun?.nextSettingMillis\n", "val rising: Long? = null\n        val setting: Long? = null\n", "weather",
     {"night hours follow Home Assistant's sunrise and sunset, not the clock",
      "sun times read a while ago roll forward by whole days"}),
    ("weather-sun-rollover-red", main / "weather/TouchWeatherPresentation.kt",
     "if (millis > nowMillis) millis else millis + ((nowMillis - millis) / DAY_MILLIS + 1) * DAY_MILLIS", "millis + 0L * nowMillis", "weather",
     {"sun times read a while ago roll forward by whole days"}),
    ("weather-axis-midnight-red", main / "weather/TouchWeatherPresentation.kt",
     "axisLabel = if (k % every == 0 && local.hour != 0)", "axisLabel = if (k % every == 0)", "weather",
     {"midnight starts the new day by name and the axis never says Midnight"}),
    ("weather-week-day-glyph-red", main / "weather/TouchWeatherPresentation.kt",
     "glyph = glyph(forecast.condition, night = false),", "glyph = glyph(forecast.condition, night = true),", "weather",
     {"the week starts today and always draws days as days, with rain as a chance or an amount"}),
    ("weather-facts-order-red", main / "weather/TouchWeatherPresentation.kt",
     ".sortedBy { it.second }.forEach { (label, millis) ->", ".sortedBy { it.first }.forEach { (label, millis) ->", "weather",
     {"facts list wind, humidity and UV, the next sun event first, then pressure"}),
    ("weather-contrast-red", main / "weather/TouchWeatherColors.kt",
     "rain = 0xFF1D5487.toInt()", "rain = 0xFF2C67A0.toInt()", "weather-colors",
     {"weather text keeps four and a half to one on the sky, field and ground by day and night"}),
    ("weather-curve-overshoot-red", main / "weather/TouchWeatherColors.kt",
     "            if (r > 9.0) {", "            if (r > 900.0) {", "weather-colors",
     {"the curve passes through every reading and never overshoots one"}),
    ("weather-sun-sensor-red", main / "weather/TouchSunRepository.kt",
     "TouchSunParser.fromSensor(get(\"sensor.sun_next_rising\", headers, deadline)),", "(null as Long?),", "weather-sun",
     {"without sun dot sun's attributes the timestamp sensors stand in"}),
    ("weather-draw-allocation-red", main / "weather/TouchWeatherView.kt",
     "canvas.drawText(tempLabels[k], x, y - lift, tempText)", "canvas.drawText(\"${hour.temperature}°\", x, y - lift, tempText)", "weather-view",
     {"drawing allocates nothing, labels and paths are built when the data or size changes"}),
    ("weather-parser-rain-red", shared_main / "weather/HomeAssistantWeatherParser.kt",
     "precipitation = item.optionalDouble(\"precipitation\"),", "precipitation = null,", "weather-parser",
     {"parses met no rain amounts, hourly UV and the rain unit Home Assistant converted them to"}),
    ("weather-codec-rain-red", shared_main / "weather/WeatherSnapshotCodec.kt",
     ".putNullable(\"precipitation\", forecast.precipitation)", ".putNullable(\"precipitation\", null)", "weather-codec",
     {"round trips rain amounts, hourly UV and the rain unit"}),
]


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def task_of(key: str) -> str:
    return CLASSES[key][2] if len(CLASSES[key]) > 2 else "touchframe"


def run(label: str, keys: list[str], expected_failures: set[str]) -> dict:
    groups = {task: [key for key in keys if task_of(key) == task] for task in TASKS}
    command = ["./gradlew", "--no-daemon", "--max-workers=2", "--continue"]
    for task, members in groups.items():
        if not members:
            continue
        if TASKS[task][1].exists():
            shutil.rmtree(TASKS[task][1])
        command += [TASKS[task][0], "--rerun"]
        for key in members:
            command += ["--tests", CLASSES[key][0]]
    log = out / f"{label}.log"
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=frame, stdout=stream, stderr=subprocess.STDOUT)
    cases = []
    for key in keys:
        xmls = list(TASKS[task_of(key)][1].rglob(f"TEST-{CLASSES[key][0]}.xml"))
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


test_hashes = {key: digest(spec[1]) for key, spec in CLASSES.items()}
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
if {key: digest(spec[1]) for key, spec in CLASSES.items()} != test_hashes:
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
