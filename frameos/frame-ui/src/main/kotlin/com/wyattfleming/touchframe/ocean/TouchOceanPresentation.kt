package com.wyattfleming.touchframe.ocean

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** US display units, matching Ebbward's US formatting. */
object TouchOceanUnits {
    private const val FEET_PER_METER = 3.28084
    private val COMPASS = listOf(
        "north", "north-northeast", "northeast", "east-northeast", "east", "east-southeast", "southeast", "south-southeast",
        "south", "south-southwest", "southwest", "west-southwest", "west", "west-northwest", "northwest", "north-northwest",
    )

    fun fahrenheit(celsius: Double): Int = (celsius * 9.0 / 5.0 + 32.0).roundToInt()
    fun feet(meters: Double): String = String.format(Locale.US, "%.1f", meters * FEET_PER_METER)
    fun mph(kmh: Double): Int = (kmh * 0.621371).roundToInt()

    /** Sixteen-point compass words for a bearing the wind or swell comes from. */
    fun compassWords(degrees: Double?): String? {
        degrees ?: return null
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        return COMPASS[(normalized / 22.5).roundToInt() % 16]
    }
}

/** Tide arithmetic on Ebbward's 6-minute NOAA prediction curve and its high/low events. */
object TouchOceanTides {
    /** The gauge tops out at 6 ft, or the next 2 ft above a bigger range (Ebbward `gaugeMaxM`). */
    fun gaugeMaxM(curve: List<TouchOceanTimed>): Double {
        val highest = curve.maxOfOrNull { it.value } ?: 0.0
        return max(1.8288, ceil(highest / 0.6096) * 0.6096)
    }

    fun heightAt(curve: List<TouchOceanTimed>, millis: Long): Double? {
        if (curve.size < 2 || millis < curve.first().epochMillis || millis > curve.last().epochMillis) return null
        val index = curve.indexOfFirst { it.epochMillis >= millis }
        if (index <= 0) return curve[0].value
        val a = curve[index - 1]
        val b = curve[index]
        if (b.epochMillis == a.epochMillis) return b.value
        return a.value + (b.value - a.value) * (millis - a.epochMillis) / (b.epochMillis - a.epochMillis).toDouble()
    }

    /** Rising when the water is higher half an hour on; null when the curve does not cover both points. */
    fun rising(curve: List<TouchOceanTimed>, millis: Long): Boolean? {
        val now = heightAt(curve, millis) ?: return null
        val later = heightAt(curve, millis + 30 * 60_000L) ?: return null
        return later > now
    }

    fun next(tides: List<TouchOceanTide>, millis: Long, high: Boolean): TouchOceanTide? =
        tides.firstOrNull { it.epochMillis > millis && it.high == high }
}

/** Ebbward's sky keyframes (`waterColumn.skyAt`) and sun/moon arc (`skyBody`), plus Touch's bedroom dimming. */
object TouchOceanSky {
    data class Sky(val top: Int, val horizon: Int)
    data class Body(val moon: Boolean, val progress: Double)

    fun at(hour: Double, sunrise: Double?, sunset: Double?, night: Boolean): Sky {
        var top = 0xFF9DBFCC.toInt()
        var horizon = 0xFFDCEAE9.toInt()
        if (sunrise != null && sunset != null && sunset > sunrise) {
            val keys = listOf(
                Triple(sunrise - 1.2, 0xFF0E2A3A, 0xFF1E4658), Triple(sunrise, 0xFF5E7E93, 0xFFEBC3A5),
                Triple(sunrise + 1.3, 0xFFBCDBE4, 0xFFEEF4EC), Triple((sunrise + sunset) / 2, 0xFFA9D3DF, 0xFFE4F2F1),
                Triple(sunset - 1.4, 0xFFB7CEDA, 0xFFF1D8BD), Triple(sunset, 0xFF50677F, 0xFFE6AB8E),
                Triple(sunset + 1, 0xFF1B3549, 0xFF3E5A6C), Triple(sunset + 2, 0xFF0E2A3A, 0xFF1E4658),
            )
            if (hour <= keys.first().first || hour >= keys.last().first) {
                top = keys.first().second.toInt()
                horizon = keys.first().third.toInt()
            } else {
                val upper = keys.indexOfFirst { it.first >= hour }
                val a = keys[upper - 1]
                val b = keys[upper]
                val t = (hour - a.first) / (b.first - a.first)
                top = mix(a.second.toInt(), b.second.toInt(), t)
                horizon = mix(a.third.toInt(), b.third.toInt(), t)
            }
        }
        if (!night) return Sky(top, horizon)
        val teal = 0xFF0E2826.toInt()
        return Sky(dim(mix(top, teal, 0.62)), dim(mix(horizon, teal, 0.5)))
    }

    fun body(hour: Double, sunrise: Double?, sunset: Double?): Body? {
        if (sunrise == null || sunset == null || sunset <= sunrise) return null
        if (hour in sunrise..sunset) return Body(moon = false, progress = (hour - sunrise) / (sunset - sunrise))
        val nightLength = 24 - (sunset - sunrise)
        val sinceSet = if (hour > sunset) hour - sunset else hour + 24 - sunset
        return Body(moon = true, progress = (sinceSet / nightLength).coerceIn(0.0, 1.0))
    }

    /** Touch night: every scene colour 60% toward black, so a bedroom frame stays dark. */
    fun dim(color: Int): Int = mix(color, 0xFF000000.toInt(), 0.6)

    fun mix(a: Int, b: Int, t: Double): Int {
        val clamped = t.coerceIn(0.0, 1.0)
        fun channel(shift: Int) = (((a ushr shift) and 0xFF) + (((b ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)) * clamped).roundToInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((color ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}

/** Ebbward's swell shape (`oceanGeometry.periodicWaveY`): a wave plus its first harmonic, periodic in one wavelength. */
object TouchOceanWaves {
    fun y(x: Double, amplitude: Double, wavelength: Double, offset: Double): Double =
        amplitude * sin(2 * PI * x / wavelength + offset) + 0.32 * amplitude * sin(4 * PI * x / wavelength + offset * 2)

    /** Ebbward `waveAmplitude`: offshore height to a calm on-screen amplitude in points (before the wall scale). */
    fun amplitude(waveM: Double?): Double = if (waveM == null) 3.0 else (waveM * 3.28084 * 2.9).coerceIn(2.0, 16.0)

    /** Where the waterline sits between the gauge's 0 ft (1.0) and its top (0.0). */
    fun waterlineFraction(tideM: Double?, gaugeMaxM: Double): Double =
        if (tideM == null) 0.5 else 1.0 - (tideM / gaugeMaxM).coerceIn(0.0, 1.0)

    /**
     * A wind streak's left edge within its wrap-around span. Every streak drifts at the wind's speed from its own start,
     * so the spacing set at time zero holds: rows a few dp apart that drift at different speeds bunch into a ≡.
     */
    fun streakX(index: Int, seconds: Double, span: Double, spacing: Double, speed: Double, direction: Int): Double =
        (((index * spacing + direction * seconds * speed) % span) + span) % span
}

data class TouchOceanStat(val label: String, val value: String, val unit: String, val detail: String?)

data class TouchOceanChartEvent(val epochMillis: Long, val heightM: Double, val high: Boolean, val height: String, val time: String)

data class TouchOceanChart(
    val fromMillis: Long,
    val toMillis: Long,
    val nowMillis: Long,
    val nowHeightM: Double,
    val points: List<TouchOceanTimed>,
    val events: List<TouchOceanChartEvent>,
    val minM: Double,
    val maxM: Double,
    val ticks: List<Pair<Long, String>>,
)

data class TouchOceanSceneSpec(
    val hour: Double,
    val sunrise: Double?,
    val sunset: Double?,
    val waterlineFraction: Double,
    val amplitude: Double,
    val periodS: Double,
    val windKmh: Double,
    val windTowardEast: Boolean,
)

data class TouchOceanScreen(
    val title: String,
    val place: String?,
    val skyLine: String?,
    val water: String?,
    val summary: String,
    val stats: List<TouchOceanStat>,
    val tideNow: String?,
    val tideTrend: String?,
    val tideNext: List<String>,
    val chart: TouchOceanChart?,
    val source: String,
    val updated: String,
    val scene: TouchOceanSceneSpec,
)

/** Turns one Ebbward response into the wall's words and numbers. Pure; the clock and zone are passed in. */
object TouchOceanPresenter {
    fun present(conditions: TouchOceanConditions, nowMillis: Long, fallbackZone: ZoneId, twentyFourHour: Boolean): TouchOceanScreen {
        val zone = conditions.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
        val clock = DateTimeFormatter.ofPattern(if (twentyFourHour) "H:mm" else "h:mm a", Locale.US).withZone(zone)
        val hourOnly = DateTimeFormatter.ofPattern(if (twentyFourHour) "H:00" else "h a", Locale.US).withZone(zone)
        fun time(millis: Long) = clock.format(Instant.ofEpochMilli(millis))

        val swellFrom = TouchOceanUnits.compassWords(conditions.waveFromDeg)
        val windFrom = TouchOceanUnits.compassWords(conditions.windFromDeg)
        val high = TouchOceanTides.next(conditions.tides, nowMillis, high = true)
        val low = TouchOceanTides.next(conditions.tides, nowMillis, high = false)
        val tideNow = TouchOceanTides.heightAt(conditions.tideCurve, nowMillis)
        val rising = TouchOceanTides.rising(conditions.tideCurve, nowMillis)

        val skyLine = listOfNotNull(
            conditions.airC?.let { "Air ${TouchOceanUnits.fahrenheit(it)}°" },
            conditions.windKmh?.let { "wind ${TouchOceanUnits.mph(it)} mph${windFrom?.let { from -> " from the $from" } ?: ""}" },
        ).joinToString(", ").ifEmpty { null }

        val stats = buildList {
            conditions.waveM?.let { wave ->
                val detail = listOfNotNull(conditions.wavePeriodS?.let { "${it.roundToInt()} s" }, swellFrom?.let { "from the $it" }).joinToString(", ")
                add(TouchOceanStat("Waves", TouchOceanUnits.feet(wave), "ft", detail.ifEmpty { null }))
            }
            conditions.windKmh?.let { wind ->
                add(TouchOceanStat("Wind", TouchOceanUnits.mph(wind).toString(), "mph", conditions.gustKmh?.let { "Gusts to ${TouchOceanUnits.mph(it)}" }))
            }
            sunStat(conditions.days, nowMillis, ::time)?.let { add(it) }
        }

        val zoneNow = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val hour = zoneNow.hour + zoneNow.minute / 60.0
        val today = conditions.days.firstOrNull { it.date == zoneNow.toLocalDate().toString() }
        fun hourOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).let { it.hour + it.minute / 60.0 }
        val sunrise = today?.let { hourOf(it.sunriseMillis) }
        val sunset = today?.let { hourOf(it.sunsetMillis).let { set -> if (sunrise != null && set < sunrise) set + 24 else set } }

        return TouchOceanScreen(
            title = conditions.spotName,
            place = conditions.place,
            skyLine = skyLine,
            water = conditions.waterC?.let { TouchOceanUnits.fahrenheit(it).toString() },
            summary = summary(conditions, swellFrom, windFrom, high?.let { time(it.epochMillis) }),
            stats = stats,
            tideNow = tideNow?.let { "${TouchOceanUnits.feet(it)} ft" },
            tideTrend = rising?.let { if (it) "and rising" else "and falling" },
            tideNext = listOfNotNull(low, high).sortedBy { it.epochMillis }.map {
                "${if (it.high) "High" else "Low"} ${time(it.epochMillis)}, ${TouchOceanUnits.feet(it.heightM)} ft"
            },
            chart = tideNow?.let { chart(conditions, nowMillis, it, ::time, hourOnly, zone) },
            source = listOfNotNull(
                "Conditions from NOAA via Ebbward",
                conditions.tideStation?.let { "tide prediction for $it" },
            ).joinToString("; ") + ".",
            updated = time(conditions.generatedAtMillis),
            scene = TouchOceanSceneSpec(
                hour = hour,
                sunrise = sunrise,
                sunset = sunset,
                waterlineFraction = TouchOceanWaves.waterlineFraction(tideNow, TouchOceanTides.gaugeMaxM(conditions.tideCurve)),
                amplitude = TouchOceanWaves.amplitude(conditions.waveM),
                periodS = conditions.wavePeriodS?.takeIf { it > 0 } ?: 10.0,
                windKmh = conditions.windKmh ?: 0.0,
                windTowardEast = conditions.windFromDeg?.let { it > 180 && it < 360 } ?: false,
            ),
        )
    }

    /** Ebbward's default Today sentence (`waterColumn.ts`), without its sea-breeze clause. */
    fun summary(conditions: TouchOceanConditions, swellFrom: String?, windFrom: String?, highTide: String?): String {
        val clauses = listOfNotNull(
            conditions.waveM?.let { wave ->
                if (swellFrom != null) "a ${if (wave < 1.0) "small" else if (wave < 1.8) "moderate" else "larger"} $swellFrom swell"
                else "waves ${TouchOceanUnits.feet(wave)} ft offshore"
            },
            conditions.windKmh?.let { "wind ${TouchOceanUnits.mph(it)} mph${windFrom?.let { from -> " from the $from" } ?: ""}" },
            highTide?.let { "high tide $it" },
            conditions.waterC?.let { "water near ${TouchOceanUnits.fahrenheit(it)}°" },
        )
        if (clauses.isEmpty()) return "Current readings are incomplete right now."
        val sentence = clauses.joinToString(", ")
        return sentence.replaceFirstChar { it.uppercase(Locale.US) } + "."
    }

    private fun sunStat(days: List<TouchOceanSunDay>, nowMillis: Long, time: (Long) -> String): TouchOceanStat? {
        val upcoming = days.flatMap { listOf("Sunrise" to it.sunriseMillis, "Sunset" to it.sunsetMillis) }
            .filter { it.second > nowMillis }.sortedBy { it.second }
        val next = upcoming.firstOrNull() ?: return null
        val parts = time(next.second).split(' ')
        val after = upcoming.getOrNull(1)?.let { "${it.first} ${time(it.second)}" }
        return TouchOceanStat(next.first, parts[0], parts.getOrElse(1) { "" }, after)
    }

    private fun chart(
        conditions: TouchOceanConditions,
        nowMillis: Long,
        nowHeight: Double,
        time: (Long) -> String,
        hourOnly: DateTimeFormatter,
        zone: ZoneId,
    ): TouchOceanChart {
        val from = nowMillis - 6 * HOUR
        val to = nowMillis + 18 * HOUR
        val margin = 6 * 60_000L
        val points = conditions.tideCurve.filter { it.epochMillis in (from - margin)..(to + margin) }
        val window = points.ifEmpty { conditions.tideCurve }
        val highest = window.maxOfOrNull { it.value } ?: nowHeight
        val lowest = window.minOfOrNull { it.value } ?: nowHeight
        val padding = max(0.06, (highest - lowest) * 0.08)
        // Ticks on local 3-hour marks (12 AM, 3 AM, 6 AM, ...), the first one at or after the window start.
        val start = Instant.ofEpochMilli(from).atZone(zone).withMinute(0).withSecond(0).withNano(0).let { if (it.toInstant().toEpochMilli() < from) it.plusHours(1) else it }
        val firstTick = start.plusHours(((3 - start.hour % 3) % 3).toLong()).toInstant().toEpochMilli()
        return TouchOceanChart(
            fromMillis = from,
            toMillis = to,
            nowMillis = nowMillis,
            nowHeightM = nowHeight,
            points = points,
            events = conditions.tides.filter { it.epochMillis in (from + 1)..(to - 1) }.map {
                TouchOceanChartEvent(it.epochMillis, it.heightM, it.high, "${TouchOceanUnits.feet(it.heightM)} ft", time(it.epochMillis))
            },
            // Fitted to this window (not the 0-6 ft gauge) so a day's swing fills the chart; labels carry the real heights.
            minM = lowest - padding,
            maxM = highest + padding,
            ticks = generateSequence(firstTick) { it + 3 * HOUR }.takeWhile { it <= to }
                .map { it to hourOnly.format(Instant.ofEpochMilli(it)) }.toList(),
        )
    }

    private const val HOUR = 3_600_000L
}
