package com.wyattfleming.touchframe.weather

import com.wyattfleming.frameos.weather.WeatherCondition
import com.wyattfleming.frameos.weather.WeatherForecast
import com.wyattfleming.frameos.weather.WeatherSnapshot
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Glyphs the Weather page draws, in Touch's 24-unit line style. */
enum class TouchWeatherGlyph { SUN, MOON, PART_SUN, PART_MOON, CLOUD, FOG, RAIN, SNOW, STORM, WIND }

/** Home Assistant's next sunrise and sunset; either may be missing. */
data class TouchSunTimes(val nextRisingMillis: Long?, val nextSettingMillis: Long?) {
    /**
     * The same events moved on by whole days until each is still ahead of [nowMillis]: times read a while ago stay
     * right to within a minute or two, instead of turning every later hour into night.
     */
    fun after(nowMillis: Long): TouchSunTimes = TouchSunTimes(nextRisingMillis?.let { ahead(it, nowMillis) }, nextSettingMillis?.let { ahead(it, nowMillis) })

    private fun ahead(millis: Long, nowMillis: Long): Long =
        if (millis > nowMillis) millis else millis + ((nowMillis - millis) / DAY_MILLIS + 1) * DAY_MILLIS

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60_000L
    }
}

enum class TouchWeatherConnection { LIVE, OFFLINE, SIGN_IN_REQUIRED, NOT_SET_UP }

data class TouchWeatherViewState(
    val snapshot: WeatherSnapshot? = null,
    val sun: TouchSunTimes? = null,
    val connection: TouchWeatherConnection = TouchWeatherConnection.LIVE,
    val loading: Boolean = false,
    val location: String? = null,
    /** A host's own words for the empty page ("Connecting to Home Assistant"); the forecast, when there is one, wins. */
    val message: String? = null,
)

data class TouchWeatherFact(val label: String, val value: String)

/** One run of a sentence: numbers are bold ink, the words around them muted. */
data class TouchWeatherSpan(val text: String, val bold: Boolean = false)

data class TouchWeatherHour(
    val epochMillis: Long,
    val temperature: Int,
    val glyph: TouchWeatherGlyph?,
    val night: Boolean,
    /** "Now", then an hour every few columns; null leaves the column unlabelled. */
    val axisLabel: String?,
    /** The new day's name on the first hour after midnight. */
    val dayStart: String?,
)

/** Consecutive hours of likely rain, drawn as one bar under the curve: [end] is exclusive, [weight] 0–1 sets its thickness. */
data class TouchWeatherRain(val start: Int, val end: Int, val weight: Float, val label: String)

data class TouchWeatherDay(
    val label: String,
    val glyph: TouchWeatherGlyph?,
    val high: Int,
    val low: Int?,
    val rain: String?,
    val today: Boolean,
)

data class TouchWeatherScreen(
    val temperature: String,
    val glyph: TouchWeatherGlyph?,
    val condition: String?,
    val detail: List<TouchWeatherSpan>,
    val rain: String?,
    val facts: List<TouchWeatherFact>,
    val status: String,
    val hours: List<TouchWeatherHour>,
    val rainRuns: List<TouchWeatherRain>,
    /** The curve's vertical window: [floor] to [floor] + [range] degrees, never under [TouchWeatherPresenter.MIN_RANGE_F]. */
    val floor: Double,
    val range: Double,
    val days: List<TouchWeatherDay>,
    /** Fahrenheit, for the temperature colour ramp, whatever unit the frame shows. */
    val fahrenheit: Boolean,
    val hoursDescription: String,
    val weekDescription: String,
)

/**
 * Turns a Home Assistant forecast into the Weather page: now in words and numbers, the next 24 hours as one curve,
 * and the week as a band of highs and lows. Pure, so every sentence is tested.
 */
object TouchWeatherPresenter {
    /** A calm day keeps a calm line: the curve's window is never shorter than this many degrees. */
    const val MIN_RANGE_F = 12.0
    const val MIN_RANGE_C = 7.0
    const val HOURS = 24

    /** Where the next turn of the curve is looked for: the coming 15 hours. */
    private const val TURN_WINDOW = 15

    /** A chance of rain this high is named in the sentence at the top; lower chances only show as bars. */
    private const val SAY_RAIN_PERCENT = 30
    private const val BAR_RAIN_PERCENT = 10

    /** An hour this wet (inches) draws the thickest bar. */
    private const val HEAVY_INCHES_PER_HOUR = 0.1
    private const val MM_PER_INCH = 25.4

    fun present(
        state: TouchWeatherViewState,
        nowMillis: Long,
        zone: ZoneId,
        twentyFour: Boolean,
        wall: Boolean,
        locale: Locale = Locale.getDefault(),
    ): TouchWeatherScreen? {
        val snapshot = state.snapshot ?: return null
        val current = snapshot.current
        val sun = state.sun?.after(nowMillis)
        val clock = DateTimeFormatter.ofPattern(if (twentyFour) "H:mm" else "h:mm a", locale)
        val fahrenheit = !current.temperatureUnit.contains("C", ignoreCase = true)
        val hours = hours(snapshot, sun, nowMillis, zone, twentyFour, wall, locale)
        val temps = hours.map { it.temperature }
        val minRange = if (fahrenheit) MIN_RANGE_F else MIN_RANGE_C
        val low = temps.minOrNull() ?: current.temperature.roundToInt()
        val high = temps.maxOrNull() ?: current.temperature.roundToInt()
        val range = maxOf((high - low).toDouble(), minRange)
        val rainingNow = current.condition in RAINING
        val wet = rainHours(snapshot, nowMillis, rainingNow)
        val unit = current.precipitationUnit ?: "in"
        val rainRuns = rainRuns(wet, unit)
        val turn = turn(hours, zone, twentyFour, locale)
        val rainSentence = rainSentence(wet, hours, unit, zone, twentyFour, locale)
        val days = days(snapshot.daily, nowMillis, zone, unit, locale)
        val updated = current.updatedAtEpochMillis.takeIf { it > 0L }?.let { clock.format(Instant.ofEpochMilli(it).atZone(zone)) }
        return TouchWeatherScreen(
            temperature = current.temperature.roundToInt().toString(),
            glyph = glyph(current.condition, hours.firstOrNull()?.night ?: (current.condition == WeatherCondition.CLEAR_NIGHT)),
            condition = conditionName(current.condition),
            detail = buildList {
                current.apparentTemperature?.let {
                    add(TouchWeatherSpan("Feels like "))
                    add(TouchWeatherSpan("${it.roundToInt()}°", bold = true))
                    add(TouchWeatherSpan(if (turn != null) ". " else ""))
                }
                turn?.let { (label, value, time) ->
                    add(TouchWeatherSpan("$label "))
                    add(TouchWeatherSpan(value, bold = true))
                    add(TouchWeatherSpan(" at $time"))
                }
            },
            rain = rainSentence,
            facts = facts(snapshot, sun, nowMillis, zone, clock),
            status = status(state, updated),
            hours = hours,
            rainRuns = rainRuns,
            floor = (low + high) / 2.0 - range / 2.0,
            range = range,
            days = days,
            fahrenheit = fahrenheit,
            hoursDescription = listOfNotNull(
                "Next 24 hours",
                turn?.let { (label, value, time) -> "${label.lowercase(locale)} $value at $time" },
                rainSentence,
            ).joinToString(", ") + ".",
            weekDescription = days.joinToString(". ") { day ->
                listOfNotNull(day.label, "high ${day.high}°", day.low?.let { "low $it°" }, day.rain?.let { "rain $it" }).joinToString(", ")
            } + ".",
        )
    }

    /** Now from the current reading, then each forecast hour after it, up to [HOURS] columns. */
    fun hours(
        snapshot: WeatherSnapshot,
        sun: TouchSunTimes?,
        nowMillis: Long,
        zone: ZoneId,
        twentyFour: Boolean,
        wall: Boolean,
        locale: Locale = Locale.getDefault(),
    ): List<TouchWeatherHour> {
        val every = if (wall) 2 else 3
        val nowNight = isNight(nowMillis, sun, zone, snapshot.current.condition)
        val points = ArrayList<TouchWeatherHour>(HOURS)
        points.add(TouchWeatherHour(nowMillis, snapshot.current.temperature.roundToInt(),
            glyph(snapshot.current.condition, nowNight), nowNight, "Now", null))
        upcoming(snapshot.hourly, nowMillis).take(HOURS - 1).forEach { (millis, forecast) ->
            val local = Instant.ofEpochMilli(millis).atZone(zone)
            val k = points.size
            val night = isNight(millis, sun, zone, forecast.condition)
            points.add(TouchWeatherHour(
                epochMillis = millis,
                temperature = forecast.temperature.roundToInt(),
                glyph = glyph(forecast.condition, night),
                night = night,
                axisLabel = if (k % every == 0 && local.hour != 0) hourLabel(millis, zone, twentyFour, locale) else null,
                dayStart = if (local.hour == 0) local.dayOfWeek.getDisplayName(TextStyle.FULL, locale) else null,
            ))
        }
        return points
    }

    /**
     * Night by Home Assistant's sun: before the next sunrise when that comes first, between sunset and the following
     * sunrise otherwise. Without sun times a clear-night reading is night and other hours follow Touch's 19:00–06:59.
     */
    fun isNight(millis: Long, sun: TouchSunTimes?, zone: ZoneId, condition: WeatherCondition? = null): Boolean {
        val rising = sun?.nextRisingMillis
        val setting = sun?.nextSettingMillis
        if (rising != null && setting != null) {
            return if (rising < setting) millis < rising || millis >= setting else millis in setting until rising
        }
        if (condition == WeatherCondition.CLEAR_NIGHT) return true
        val hour = Instant.ofEpochMilli(millis).atZone(zone).hour
        return hour < 7 || hour >= 19
    }

    fun glyph(condition: WeatherCondition, night: Boolean): TouchWeatherGlyph? = when (condition) {
        WeatherCondition.SUNNY, WeatherCondition.CLEAR_NIGHT -> if (night) TouchWeatherGlyph.MOON else TouchWeatherGlyph.SUN
        WeatherCondition.PARTLY_CLOUDY -> if (night) TouchWeatherGlyph.PART_MOON else TouchWeatherGlyph.PART_SUN
        WeatherCondition.CLOUDY -> TouchWeatherGlyph.CLOUD
        WeatherCondition.FOG -> TouchWeatherGlyph.FOG
        WeatherCondition.RAINY, WeatherCondition.POURING -> TouchWeatherGlyph.RAIN
        WeatherCondition.SNOWY, WeatherCondition.SNOWY_RAINY -> TouchWeatherGlyph.SNOW
        WeatherCondition.LIGHTNING, WeatherCondition.LIGHTNING_RAINY, WeatherCondition.HAIL -> TouchWeatherGlyph.STORM
        WeatherCondition.WINDY, WeatherCondition.WINDY_VARIANT -> TouchWeatherGlyph.WIND
        WeatherCondition.EXCEPTIONAL, WeatherCondition.UNKNOWN -> null
    }

    /** Words for the reading now: a clear night is just "Clear"; an unknown reading says nothing. */
    fun conditionName(condition: WeatherCondition): String? = when (condition) {
        WeatherCondition.CLEAR_NIGHT -> "Clear"
        WeatherCondition.UNKNOWN -> null
        else -> condition.displayName
    }

    /**
     * The next turn of the curve within 15 hours: the low when it comes before the high (or the high is now), the
     * high otherwise. A flat stretch, or a turn that is now, says nothing.
     */
    fun turn(hours: List<TouchWeatherHour>, zone: ZoneId, twentyFour: Boolean, locale: Locale = Locale.getDefault()): Triple<String, String, String>? {
        val span = hours.take(TURN_WINDOW)
        if (span.size < 2) return null
        val hi = span.indices.maxByOrNull { span[it].temperature } ?: return null
        val lo = span.indices.minByOrNull { span[it].temperature } ?: return null
        if (span[hi].temperature == span[lo].temperature) return null
        val (label, index) = if ((hi > lo && lo != 0) || hi == 0) "Low" to lo else "High" to hi
        if (index == 0) return null
        return Triple(label, "${span[index].temperature}°", sentenceHour(span[index].epochMillis, zone, twentyFour, locale))
    }

    /**
     * One wet-hour reading per column: a chance when Home Assistant sends one, otherwise the expected amount.
     * [counted] is false for the "Now" column's copy of the next hour, which draws but never adds to a total.
     */
    data class WetHour(val index: Int, val epochMillis: Long, val percent: Int?, val amount: Double?, val counted: Boolean = true)

    private fun rainHours(snapshot: WeatherSnapshot, nowMillis: Long, rainingNow: Boolean): List<WetHour> {
        val upcoming = upcoming(snapshot.hourly, nowMillis).take(HOURS - 1)
            .mapIndexed { i, (millis, forecast) -> WetHour(i + 1, millis, forecast.precipitationProbability, forecast.precipitation) }
        // Column 0 is the reading now: while it rains it joins the next hour's run instead of standing dry.
        val next = upcoming.firstOrNull()
        return if (rainingNow && next != null) listOf(next.copy(index = 0, epochMillis = nowMillis, counted = false)) + upcoming else upcoming
    }

    /**
     * Bars under the curve. With chances, a run holds hours within five points of each other and is labelled with its
     * chance; with amounts, a run is consecutive wet hours, labelled with its total.
     */
    fun rainRuns(wet: List<WetHour>, unit: String): List<TouchWeatherRain> =
        if (wet.any { it.percent != null }) chanceRuns(wet) else amountRuns(wet, unit)

    private fun chanceRuns(wet: List<WetHour>): List<TouchWeatherRain> {
        val runs = ArrayList<TouchWeatherRain>()
        var start = -1
        var end = -1
        var percent = 0
        for (hour in wet) {
            val p = hour.percent ?: 0
            if (p >= BAR_RAIN_PERCENT && start >= 0 && hour.index == end && abs(p - percent) < 5) {
                end = hour.index + 1
                continue
            }
            if (start >= 0) runs.add(TouchWeatherRain(start, end, (percent / 100f).coerceIn(0.1f, 1f), "$percent%"))
            start = -1
            if (p >= BAR_RAIN_PERCENT) {
                start = hour.index
                end = hour.index + 1
                percent = p
            }
        }
        if (start >= 0) runs.add(TouchWeatherRain(start, end, (percent / 100f).coerceIn(0.1f, 1f), "$percent%"))
        return runs
    }

    private fun amountRuns(wet: List<WetHour>, unit: String): List<TouchWeatherRain> {
        val runs = ArrayList<TouchWeatherRain>()
        var start = -1
        var end = -1
        var total = 0.0
        var peak = 0.0
        fun close() {
            if (start >= 0) runs.add(TouchWeatherRain(start, end, (inches(peak, unit) / HEAVY_INCHES_PER_HOUR).toFloat().coerceIn(0.1f, 1f), amount(total, unit)))
            start = -1
        }
        for (hour in wet) {
            val amount = hour.amount ?: 0.0
            if (amount <= 0.0) {
                close()
                continue
            }
            if (start < 0 || hour.index != end) {
                close()
                start = hour.index
                total = 0.0
                peak = 0.0
            }
            end = hour.index + 1
            if (hour.counted) total += amount
            peak = maxOf(peak, amount)
        }
        close()
        return runs
    }

    /**
     * Rain in the sentence at the top. A chance names the first hour at 30% or more and the highest chance after it
     * ("Rain 40% from midnight"); an amount names the first wet stretch, when it ends and about how much falls.
     */
    private fun rainSentence(wet: List<WetHour>, hours: List<TouchWeatherHour>, unit: String, zone: ZoneId, twentyFour: Boolean, locale: Locale): String? {
        if (wet.any { it.percent != null }) {
            val first = wet.firstOrNull { (it.percent ?: 0) >= SAY_RAIN_PERCENT } ?: return null
            val peak = wet.filter { it.index >= first.index }.maxOf { it.percent ?: 0 }
            val whenText = if (first.index == 0) "now" else "from ${sentenceHour(first.epochMillis, zone, twentyFour, locale)}"
            return "Rain $peak% $whenText"
        }
        val run = rainRuns(wet, unit).firstOrNull() ?: return null
        val total = wet.filter { it.counted && it.index in run.start until run.end }.sumOf { it.amount ?: 0.0 }
        val endText = hours.getOrNull(run.end)?.let { sentenceHour(it.epochMillis, zone, twentyFour, locale) }
        val light = inches(total, unit) < LIGHT_TOTAL_INCHES
        val start = hours.getOrNull(run.start)?.epochMillis ?: return null
        val span = when {
            run.start == 0 -> endText?.let { "until $it" } ?: "now"
            else -> "from ${sentenceHour(start, zone, twentyFour, locale)}" + (endText?.let { " to $it" } ?: "")
        }
        return if (light) "Light rain $span" else "Rain $span, about ${amount(total, unit)}"
    }

    private const val LIGHT_TOTAL_INCHES = 0.02

    private fun inches(value: Double, unit: String): Double = if (unit.equals("mm", ignoreCase = true)) value / MM_PER_INCH else value

    /** "0.04 in", "0.2 in", "0.4 mm", "3 mm". */
    fun amount(value: Double, unit: String): String = if (unit.equals("mm", ignoreCase = true)) {
        if (value < 1.0) "${"%.1f".format(Locale.US, value)} mm" else "${value.roundToInt()} mm"
    } else {
        if (value < 0.095) "${"%.2f".format(Locale.US, value)} in" else "${"%.1f".format(Locale.US, value)} in"
    }

    /** Wind, humidity and UV, then the next sun event and the one after, then pressure: at most six. */
    private fun facts(snapshot: WeatherSnapshot, sun: TouchSunTimes?, nowMillis: Long, zone: ZoneId, clock: DateTimeFormatter): List<TouchWeatherFact> {
        val current = snapshot.current
        return buildList {
            current.windSpeed?.let { speed ->
                val unit = current.windSpeedUnit?.let { " $it" }.orEmpty()
                add(TouchWeatherFact("Wind", "${speed.roundToInt()}$unit" + (current.windBearing?.let { " ${compass(it)}" } ?: "")))
            }
            current.humidity?.let { add(TouchWeatherFact("Humidity", "${it.roundToInt()}%")) }
            current.uvIndex?.let { add(TouchWeatherFact("UV index", uv(it))) }
            val rising = sun?.nextRisingMillis?.takeIf { it > nowMillis }
            val setting = sun?.nextSettingMillis?.takeIf { it > nowMillis }
            listOfNotNull(rising?.let { "Sunrise" to it }, setting?.let { "Sunset" to it }).sortedBy { it.second }.forEach { (label, millis) ->
                add(TouchWeatherFact(label, clock.format(Instant.ofEpochMilli(millis).atZone(zone))))
            }
            current.pressure?.let { add(TouchWeatherFact("Pressure", pressure(it, current.pressureUnit))) }
        }.take(6)
    }

    /** UV by the WHO bands; a dark sky is simply 0. */
    fun uv(value: Double): String {
        val index = value.roundToInt()
        val band = when {
            index <= 0 -> return "0"
            index <= 2 -> "low"
            index <= 5 -> "moderate"
            index <= 7 -> "high"
            index <= 10 -> "very high"
            else -> "extreme"
        }
        return "$index, $band"
    }

    private fun pressure(value: Double, unit: String?): String = when {
        unit == null -> "%.0f".format(Locale.US, value)
        unit.equals("inHg", ignoreCase = true) -> "${"%.2f".format(Locale.US, value)} in"
        else -> "${value.roundToInt()} $unit"
    }

    /** Eight points of the compass: short enough to sit beside the speed. */
    fun compass(bearing: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((bearing % 360.0) + 360.0) % 360.0
        return names[((normalized / 45.0) + 0.5).toInt() % names.size]
    }

    private fun status(state: TouchWeatherViewState, updated: String?): String {
        val from = updated?.let { " Showing the forecast from $it." } ?: ""
        return when (state.connection) {
            TouchWeatherConnection.SIGN_IN_REQUIRED -> "Home Assistant needs this frame to sign in again.$from"
            TouchWeatherConnection.OFFLINE, TouchWeatherConnection.NOT_SET_UP -> "Can’t reach Home Assistant.$from"
            TouchWeatherConnection.LIVE -> listOfNotNull(state.location?.trim()?.takeIf(String::isNotEmpty)?.let { "$it." },
                updated?.let { "Updated $it" }).joinToString(" ")
        }
    }

    /** Today first, then each following day Home Assistant forecasts; a day's sky is always drawn as a day. */
    fun days(daily: List<WeatherForecast>, nowMillis: Long, zone: ZoneId, unit: String = "in", locale: Locale = Locale.getDefault()): List<TouchWeatherDay> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return daily.mapNotNull { forecast ->
            val date = parse(forecast.dateTime)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return@mapNotNull null
            if (date.isBefore(today)) return@mapNotNull null
            TouchWeatherDay(
                label = if (date == today) "Today" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                glyph = glyph(forecast.condition, night = false),
                high = forecast.temperature.roundToInt(),
                low = forecast.templow?.roundToInt(),
                rain = forecast.precipitationProbability?.takeIf { it >= BAR_RAIN_PERCENT }?.let { "$it%" }
                    ?: forecast.precipitation?.takeIf { inches(it, unit) >= MIN_DAY_INCHES }?.let { amount(it, unit) },
                today = date == today,
            )
        }.take(MAX_DAYS)
    }

    private const val MAX_DAYS = 8
    private const val MIN_DAY_INCHES = 0.01
    private val RAINING = setOf(WeatherCondition.RAINY, WeatherCondition.POURING, WeatherCondition.LIGHTNING_RAINY, WeatherCondition.SNOWY_RAINY)

    private fun upcoming(hourly: List<WeatherForecast>, nowMillis: Long): List<Pair<Long, WeatherForecast>> =
        hourly.mapNotNull { forecast -> parse(forecast.dateTime)?.let { it to forecast } }
            .filter { (millis, _) -> millis > nowMillis }
            .sortedBy { it.first }

    private fun parse(value: String): Long? = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
        null
    }

    /** Axis hours: "4 PM" or "16:00"; the axis never says Midnight because a rule and the day's name mark it. */
    fun hourLabel(millis: Long, zone: ZoneId, twentyFour: Boolean, locale: Locale = Locale.getDefault()): String {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        if (twentyFour) return "%d:00".format(locale, local.hour)
        return when (local.hour) {
            12 -> "Noon"
            else -> "${(local.hour + 11) % 12 + 1} ${if (local.hour < 12) "AM" else "PM"}"
        }
    }

    /** Hours inside a sentence: "2 PM", "noon", "midnight". */
    fun sentenceHour(millis: Long, zone: ZoneId, twentyFour: Boolean, locale: Locale = Locale.getDefault()): String {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        if (twentyFour) return "%d:00".format(locale, local.hour)
        return when (local.hour) {
            0 -> "midnight"
            12 -> "noon"
            else -> "${(local.hour + 11) % 12 + 1} ${if (local.hour < 12) "AM" else "PM"}"
        }
    }
}
