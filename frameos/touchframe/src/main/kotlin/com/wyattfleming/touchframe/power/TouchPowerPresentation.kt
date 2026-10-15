package com.wyattfleming.touchframe.power

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Everything the frame last read from Home Assistant for the Power page, at [readAtMillis]. */
data class TouchPowerSnapshot(
    val config: TouchPowerConfig,
    val schedule: TouchPowerSchedule?,
    val states: Map<String, TouchPowerState>,
    val hours: List<TouchPowerHour>,
    val laundryHistory: Map<String, List<TouchPowerState>>,
    val solarHistory: List<TouchPowerState>,
    val readAtMillis: Long,
)

enum class TouchPowerConnection { LIVE, OFFLINE, SIGN_IN_REQUIRED, NOT_SET_UP }

data class TouchPowerViewState(
    val snapshot: TouchPowerSnapshot? = null,
    val connection: TouchPowerConnection = TouchPowerConnection.LIVE,
    val loading: Boolean = false,
)

data class TouchPowerRow(val name: String, val value: String, val fraction: Float?, val tone: Tone) {
    enum class Tone { RUNNING, IDLE, OFF, MISSING }
}

/** A pin for one quarter hour of the next day: where it sits (hours from now) and its price's place on the ladder. */
data class TouchPowerPin(val hoursAhead: Double, val rank: Int)

/** One hour of the use ring, placed by hours before now. */
data class TouchPowerWedge(val startHoursAgo: Double, val endHoursAgo: Double, val kwh: Double)

data class TouchPowerDial(
    val pointerTime: String,
    val hubPrice: String?,
    val pins: List<TouchPowerPin>,
    val ranks: Int,
    val hourLabels: List<Pair<Double, String>>,
    /** Daylight from and to, in hours from now; the start is negative while the sun is up. */
    val daylight: Pair<Double, Double>?,
    val wedges: List<TouchPowerWedge>,
    val ringMaxKwh: Double,
)

data class TouchPowerSolarCard(val label: String, val value: String?, val lines: List<String>, val stale: Boolean)

data class TouchPowerScreen(
    val dial: TouchPowerDial,
    val headline: String,
    val next: String?,
    val runningTitle: String,
    val runningSummary: String?,
    val warning: String?,
    val rows: List<TouchPowerRow>,
    val note: String,
    val laundry: List<String>,
    val laundryRunning: Boolean,
    val todayKwh: String?,
    val todayCaption: String,
    val solar: TouchPowerSolarCard?,
    val legend: List<String>,
)

/** Plain, literal wording and the dial's geometry from one snapshot. Pure, so every sentence is testable. */
object TouchPowerPresenter {
    const val RUNNING_WATTS = 10.0
    const val IDLE_WATTS = 1.0
    const val BAR_MIN_SCALE_WATTS = 500.0
    const val RING_STEP_KWH = 0.5
    private const val HOUR = TouchPowerHours.HOUR_MILLIS
    private const val QUARTER = HOUR / 4

    fun present(
        snapshot: TouchPowerSnapshot,
        connection: TouchPowerConnection,
        nowMillis: Long,
        zone: ZoneId,
        twentyFour: Boolean,
        wall: Boolean,
        locale: Locale = Locale.US,
    ): TouchPowerScreen {
        val clock = DateTimeFormatter.ofPattern(if (twentyFour) "H:mm" else "h:mm a", locale)
        val wholeHour = DateTimeFormatter.ofPattern(if (twentyFour) "H:mm" else "h a", locale)
        fun time(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(clock)
        // Rate changes fall on the hour: "until 9 PM", not "until 9:00 PM".
        fun hourTime(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).let { it.format(if (it.minute == 0) wholeHour else clock) }
        val offline = connection == TouchPowerConnection.OFFLINE
        val schedule = snapshot.schedule
        val price = schedule?.at(nowMillis, zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        fun whenText(millis: Long): String =
            hourTime(millis) + if (Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() != today) " tomorrow" else ""

        // Headline: the period now and when it ends; then the next period, until when, and its price.
        val firstChange = if (schedule != null && price != null) changeAfter(schedule, nowMillis, price.period, zone) else null
        val nextPrice = if (schedule != null && firstChange != null) schedule.at(firstChange, zone) else null
        val secondChange = if (schedule != null && firstChange != null && nextPrice != null) changeAfter(schedule, firstChange, nextPrice.period, zone) else null
        val headline = when {
            price == null -> "Rates aren’t available"
            firstChange == null -> "${price.period.label} all day"
            else -> "${price.period.label} until ${whenText(firstChange)}"
        }
        val next = when {
            nextPrice == null -> null
            secondChange == null -> "Then ${nextPrice.period.label.lowercase(locale)}, ${cents(nextPrice.dollarsPerKwh)} a kWh."
            else -> "Then ${nextPrice.period.label.lowercase(locale)} until ${whenText(secondChange)}, ${cents(nextPrice.dollarsPerKwh)} a kWh."
        }

        // What is drawing power: running loads by watts, then idle, then off, then plugs with no reading.
        val rows = snapshot.config.devices.map { device -> device to snapshot.states[device.powerEntity] }
        val readings = rows.mapNotNull { (device, state) -> state?.takeIf { it.available }?.number?.let { device to it } }
        val scale = maxOf(BAR_MIN_SCALE_WATTS, readings.maxOfOrNull { it.second } ?: 0.0)
        val presentRows = rows.map { (device, state) -> row(device, state, scale, offline, ::time) }
            .sortedWith(compareBy<Pair<TouchPowerRow, Double>> { it.first.tone == TouchPowerRow.Tone.MISSING }.thenByDescending { it.second })
            .map { it.first }
        val totalWatts = readings.sumOf { it.second }
        val missing = rows.filter { (_, state) -> state == null || !state.available || state.number == null }.map { it.first.name }
        val estimated = snapshot.config.devices.filter { it.estimate }.map { it.name }

        val runningTitle = if (offline) "Last readings, ${time(snapshot.readAtMillis)}" else "Running now"
        val runningSummary = if (offline || price == null) null else buildString {
            append("${watts(totalWatts, locale)}")
            if (missing.isNotEmpty() && wall) append(" without ${missing.joinToString(", ")}")
            append(", about ${money(totalWatts / 1000.0 * price.dollarsPerKwh)} an hour")
        }
        val warning = if (offline) "Home Assistant isn’t responding" else null
        val note = when {
            offline -> if (wall) "They update when Home Assistant answers again." else "Home Assistant isn’t responding."
            !wall && missing.isNotEmpty() -> "Total leaves out ${missing.size} plug${if (missing.size == 1) "" else "s"}. Updated ${time(snapshot.readAtMillis)}."
            !wall -> "Only these plugs are metered. Updated ${time(snapshot.readAtMillis)}."
            estimated.isEmpty() -> "Only these plugs are metered. Updated ${time(snapshot.readAtMillis)}."
            else -> "Only these plugs are metered; ${estimated.joinToString(" and ").replaceFirstChar { it.lowercase(locale) }} is estimated. Updated ${time(snapshot.readAtMillis)}."
        }

        // Laundry: anything running first, then the latest finishes.
        val laundry = snapshot.config.laundry.mapNotNull { item ->
            TouchLaundry.status(item.name, snapshot.states[item.entity], snapshot.laundryHistory[item.entity].orEmpty(), nowMillis)
        }.sortedWith(compareBy<TouchLaundryStatus> { it !is TouchLaundryStatus.Running }.thenByDescending { (it as? TouchLaundryStatus.Finished)?.atMillis ?: Long.MAX_VALUE })
        val laundryLines = laundry.take(2).map { status ->
            when (status) {
                is TouchLaundryStatus.Running -> "${status.name} running"
                is TouchLaundryStatus.Finished -> {
                    val day = Instant.ofEpochMilli(status.atMillis).atZone(zone).toLocalDate()
                    "${status.name} finished ${if (day == today) "" else "yesterday "}at ${time(status.atMillis)}"
                }
            }
        }

        val midnight = TouchPowerHours.midnight(nowMillis, zone)
        val todayKwh = TouchPowerHours.since(midnight, snapshot.hours)
        val todayCaption = if (offline) "Up to ${time(snapshot.readAtMillis)}." else "Since midnight."

        val solar = TouchPowerSolarSummary.from(snapshot.solarHistory, nowMillis)?.let { s ->
            val day = Instant.ofEpochMilli(s.reportedMillis).atZone(zone).toLocalDate()
            val dayWord = when (day) {
                today -> "today"
                today.minusDays(1) -> "yesterday"
                else -> day.format(DateTimeFormatter.ofPattern("MMM d", locale))
            }
            if (s.stale) TouchPowerSolarCard("Solar", null,
                listOf("No new data from Sunrun since ${day.format(DateTimeFormatter.ofPattern("MMM d", locale))}, ${time(s.reportedMillis)}", "Sunrun often reports late."), true)
            else TouchPowerSolarCard("Solar, last 7 days", s.kwhWeek?.let { "${it.roundToInt()}" },
                listOfNotNull(s.kwhWeek?.takeIf { wall }?.let { "About ${(it / 7).roundToInt()} kWh a day." }, "Sunrun sent it $dayWord, ${time(s.reportedMillis)}."), false)
        }

        val dial = dial(snapshot, schedule, price, nowMillis, zone, twentyFour, locale, ::time)
        val dearest = schedule?.let { dearestHours(it, zone, twentyFour, locale) }
        val legend = if (wall) listOf(
            listOfNotNull(dearest?.let { "Taller pins: $it, the expensive hours." }, "Gold edge: daylight.", "Rates as modelled in Home Assistant.").joinToString(" "),
            "Inner ring: metered use each hour, last 24 hours; the dashed circle is 0.5 kWh.",
        ) else listOf(
            listOfNotNull(dearest?.let { "Taller pins: $it, the expensive hours." }, "Rates from Home Assistant.").joinToString(" "),
            "Gold edge: daylight. Ring: use by hour, last 24 hours; dashed 0.5 kWh.",
        )

        return TouchPowerScreen(
            dial = dial,
            headline = headline,
            next = next,
            runningTitle = runningTitle,
            runningSummary = runningSummary,
            warning = warning,
            rows = presentRows,
            note = note,
            laundry = laundryLines,
            laundryRunning = laundry.firstOrNull() is TouchLaundryStatus.Running,
            todayKwh = todayKwh?.let { String.format(locale, "%.1f", it) },
            todayCaption = todayCaption,
            solar = solar,
            legend = legend,
        )
    }

    /** The first hour start after [fromMillis] whose period differs from [period], within the published days. */
    fun changeAfter(schedule: TouchPowerSchedule, fromMillis: Long, period: TouchPowerPeriod, zone: ZoneId): Long? {
        var t = TouchPowerHours.hourStart(fromMillis, zone) + HOUR
        repeat(48) {
            val at = schedule.at(t, zone) ?: return null
            if (at.period != period) return t
            t += HOUR
        }
        return null
    }

    fun cents(dollars: Double): String = "${(dollars * 100).roundToInt()}¢"

    /** Under a dollar an hour reads in cents; above it, in dollars and cents. */
    fun money(dollars: Double): String {
        val cents = (dollars * 100).roundToInt()
        return if (cents < 100) "$cents¢" else String.format(Locale.US, "$%d.%02d", cents / 100, cents % 100)
    }

    fun watts(value: Double, locale: Locale): String =
        if (value >= 10_000) String.format(locale, "%.1f kW", value / 1000) else String.format(locale, "%,d W", value.roundToInt())

    /** The ring's scale: half a kWh, or the next half kWh above the busiest hour so nothing is clipped. */
    fun ringMax(kwh: List<Double>): Double = maxOf(RING_STEP_KWH, ceil((kwh.maxOrNull() ?: 0.0) / RING_STEP_KWH) * RING_STEP_KWH)

    private fun row(device: TouchPowerDevice, state: TouchPowerState?, scale: Double, offline: Boolean, time: (Long) -> String): Pair<TouchPowerRow, Double> {
        val name = if (device.estimate) "${device.name} (estimate)" else device.name
        val watts = state?.takeIf { it.available }?.number
        if (state == null || watts == null) {
            val since = state?.let { "No reading since ${time(it.lastChangedMillis)}" } ?: "No reading"
            return TouchPowerRow(name, since, null, TouchPowerRow.Tone.MISSING) to -1.0
        }
        val tone = when {
            watts >= RUNNING_WATTS -> TouchPowerRow.Tone.RUNNING
            watts >= IDLE_WATTS -> TouchPowerRow.Tone.IDLE
            else -> TouchPowerRow.Tone.OFF
        }
        val value = when {
            tone == TouchPowerRow.Tone.OFF -> "Off"
            device.estimate && watts >= 1000 -> "about ${String.format(Locale.US, "%.1f", watts / 1000)} kW"
            device.estimate -> "about ${watts.roundToInt()} W"
            else -> "${watts.roundToInt()} W"
        }
        val fraction = if (tone == TouchPowerRow.Tone.RUNNING && !offline) (watts / scale).toFloat().coerceIn(0f, 1f) else null
        return TouchPowerRow(name, value, fraction, tone) to watts
    }

    private fun dial(
        snapshot: TouchPowerSnapshot,
        schedule: TouchPowerSchedule?,
        price: TouchPowerSchedule.Price?,
        nowMillis: Long,
        zone: ZoneId,
        twentyFour: Boolean,
        locale: Locale,
        time: (Long) -> String,
    ): TouchPowerDial {
        // 96 quarter-hour pins from the quarter now under way; each pin's height is its price's place on the ladder.
        val ladder = schedule?.priceLadder.orEmpty()
        // Math.floorMod is API 24; the offset into the hour is never negative, so % is exact here.
        val firstQuarter = nowMillis - (nowMillis - TouchPowerHours.hourStart(nowMillis, zone)) % QUARTER
        val pins = if (schedule == null) emptyList() else (0 until 96).mapNotNull { i ->
            val mid = firstQuarter + i * QUARTER + QUARTER / 2
            val at = schedule.at(mid, zone) ?: return@mapNotNull null
            TouchPowerPin((mid - nowMillis) / HOUR.toDouble(), ladder.indexOf(at.dollarsPerKwh))
        }
        val hourFormat = DateTimeFormatter.ofPattern(if (twentyFour) "H:00" else "h a", locale)
        val currentHourStart = TouchPowerHours.hourStart(nowMillis, zone)
        val labels = (1..24).map { currentHourStart + it * HOUR }
            .filter { Instant.ofEpochMilli(it).atZone(zone).hour % 3 == 0 }
            .map { (it - nowMillis) / HOUR.toDouble() to Instant.ofEpochMilli(it).atZone(zone).format(hourFormat) }
        val daylight = snapshot.states[TouchPowerConfig.SUN_ENTITY]?.let(TouchPowerParser::parseSun)?.let { (rising, setting) ->
            // While the sun is up the next rising is tomorrow's; today's was about a day earlier.
            val start = if (rising < setting) rising else rising - 24 * HOUR
            (start - nowMillis) / HOUR.toDouble() to (setting - nowMillis) / HOUR.toDouble()
        }
        val wedges = snapshot.hours.mapNotNull { hour ->
            val kwh = hour.kwh ?: return@mapNotNull null
            if (kwh <= 0.0) return@mapNotNull null
            TouchPowerWedge((nowMillis - hour.startMillis) / HOUR.toDouble(), (nowMillis - hour.endMillis) / HOUR.toDouble(), kwh)
        }
        return TouchPowerDial(
            pointerTime = time(nowMillis),
            hubPrice = price?.let { cents(it.dollarsPerKwh) },
            pins = pins,
            ranks = ladder.size,
            hourLabels = labels,
            daylight = daylight,
            wedges = wedges,
            ringMaxKwh = ringMax(wedges.map { it.kwh }),
        )
    }

    /** The first run of today's dearest hours, as "4 to 9 PM"; null when every hour costs the same. */
    fun dearestHours(schedule: TouchPowerSchedule, zone: ZoneId, twentyFour: Boolean, locale: Locale): String? {
        val prices = schedule.today.map { schedule.rates[it] ?: 0.0 }
        val top = prices.maxOrNull() ?: return null
        if (prices.all { it == top }) return null
        val start = prices.indexOfFirst { it == top }
        var end = start
        while (end + 1 < 24 && prices[end + 1] == top) end++
        val endHour = (end + 1) % 24
        return if (twentyFour) "%d:00 to %d:00".format(locale, start, endHour)
        else {
            fun h(hour: Int) = "${if (hour % 12 == 0) 12 else hour % 12}"
            fun suffix(hour: Int) = if (hour < 12) "AM" else "PM"
            if (suffix(start) == suffix(endHour)) "${h(start)} to ${h(endHour)} ${suffix(endHour)}"
            else "${h(start)} ${suffix(start)} to ${h(endHour)} ${suffix(endHour)}"
        }
    }
}
