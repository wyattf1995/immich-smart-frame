package com.wyattfleming.touchframe.power

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** SCE time-of-use periods, by the names Home Assistant's `sensor.sce_tou_schedule` publishes. */
enum class TouchPowerPeriod(val key: String, val label: String) {
    SUPER_OFF_PEAK("super_off_peak", "Super off-peak"),
    OFF_PEAK("off_peak", "Off-peak"),
    MID_PEAK("mid_peak", "Mid-peak"),
    ON_PEAK("on_peak", "On-peak"),
    ;

    companion object {
        fun of(key: String?): TouchPowerPeriod? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The rate plan as Home Assistant publishes it: the period of every clock hour today and tomorrow, and each day's
 * prices in dollars per kWh. Home Assistant owns the plan; the frame never works out a period itself.
 */
data class TouchPowerSchedule(
    val date: LocalDate,
    val today: List<TouchPowerPeriod>,
    val tomorrow: List<TouchPowerPeriod>,
    val rates: Map<TouchPowerPeriod, Double>,
    val ratesTomorrow: Map<TouchPowerPeriod, Double>,
) {
    data class Price(val period: TouchPowerPeriod, val dollarsPerKwh: Double)

    /** The period and price in force at [millis], or null outside the two published days. */
    fun at(millis: Long, zone: ZoneId): Price? {
        val local = Instant.ofEpochMilli(millis).atZone(zone)
        val (hours, prices) = when (local.toLocalDate()) {
            date -> today to rates
            date.plusDays(1) -> tomorrow to ratesTomorrow
            else -> return null
        }
        val period = hours.getOrNull(local.hour) ?: return null
        return Price(period, prices[period] ?: return null)
    }

    /** Every distinct price across both days, cheapest first; a pin's height is its price's place in this list. */
    val priceLadder: List<Double> get() = (rates.values + ratesTomorrow.values).distinct().sorted()
}

/** One metered (or estimated) device the Power page lists. */
data class TouchPowerDevice(val name: String, val powerEntity: String, val energyEntity: String?, val estimate: Boolean)

/** A Home Assistant binary sensor that is on while an appliance runs. */
data class TouchPowerLaundry(val name: String, val entity: String)

/** What `sensor.frame_power` tells the frame to show; devices change in Home Assistant, never in the app. */
data class TouchPowerConfig(
    val scheduleEntity: String,
    val devices: List<TouchPowerDevice>,
    val laundry: List<TouchPowerLaundry>,
    val solarEntity: String?,
) {
    /** Read once a minute while Power is open. */
    val liveEntities: List<String>
        get() = (listOf(scheduleEntity) + devices.map { it.powerEntity } + devices.mapNotNull { it.energyEntity } +
            laundry.map { it.entity } + listOfNotNull(solarEntity, SUN_ENTITY)).distinct()

    val energyEntities: List<String> get() = devices.mapNotNull { it.energyEntity }.distinct()

    companion object {
        const val SUN_ENTITY = "sun.sun"
    }
}

/** One entity's state as Home Assistant reports it. */
data class TouchPowerState(val entityId: String, val state: String, val lastChangedMillis: Long, val attributes: JSONObject? = null) {
    val number: Double? get() = state.toDoubleOrNull()?.takeIf { it.isFinite() }
    val available: Boolean get() = state.isNotEmpty() && state != "unavailable" && state != "unknown"
}

/** Strict readers for Home Assistant's state and history answers; anything malformed is dropped, never guessed. */
object TouchPowerParser {
    const val MAX_DEVICES = 12
    const val MAX_LAUNDRY = 4
    const val MAX_NAME_CHARS = 40
    private val ENTITY = Regex("^[a-z_]+\\.[a-z0-9_]+$")

    fun isEntityId(value: String?): Boolean = value != null && value.length <= 120 && ENTITY.matches(value)

    fun time(value: String?): Long? =
        if (value.isNullOrBlank()) null else runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

    /** One `/api/states/<entity>` answer. */
    fun parseState(body: String): TouchPowerState? = runCatching { state(JSONObject(body), null) }.getOrNull()

    /** `/api/history/period` answers: one array per entity, oldest first; minimal rows omit the entity id. */
    fun parseHistory(body: String): Map<String, List<TouchPowerState>> = runCatching {
        val outer = JSONArray(body)
        val result = LinkedHashMap<String, List<TouchPowerState>>()
        for (i in 0 until outer.length()) {
            val rows = outer.optJSONArray(i) ?: continue
            val id = rows.optJSONObject(0)?.optString("entity_id")?.takeIf(::isEntityId) ?: continue
            val list = ArrayList<TouchPowerState>(rows.length())
            for (j in 0 until rows.length()) rows.optJSONObject(j)?.let { state(it, id) }?.let { list.add(it.copy(entityId = id)) }
            result[id] = list
        }
        result
    }.getOrDefault(emptyMap())

    fun parseConfig(state: TouchPowerState?): TouchPowerConfig? {
        val attributes = state?.attributes ?: return null
        val schedule = attributes.optString("schedule").takeIf(::isEntityId) ?: return null
        val devices = ArrayList<TouchPowerDevice>()
        attributes.optJSONArray("devices")?.let { array ->
            for (i in 0 until minOf(array.length(), MAX_DEVICES)) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim().take(MAX_NAME_CHARS)
                val power = item.optString("power")
                if (name.isEmpty() || !isEntityId(power)) continue
                devices.add(TouchPowerDevice(name, power, item.optString("energy").takeIf(::isEntityId), item.optBoolean("estimate", false)))
            }
        }
        if (devices.isEmpty()) return null
        val laundry = ArrayList<TouchPowerLaundry>()
        attributes.optJSONArray("laundry")?.let { array ->
            for (i in 0 until minOf(array.length(), MAX_LAUNDRY)) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim().take(MAX_NAME_CHARS)
                val entity = item.optString("entity")
                if (name.isNotEmpty() && isEntityId(entity)) laundry.add(TouchPowerLaundry(name, entity))
            }
        }
        return TouchPowerConfig(schedule, devices, laundry, attributes.optString("solar").takeIf(::isEntityId))
    }

    fun parseSchedule(state: TouchPowerState?): TouchPowerSchedule? {
        val attributes = state?.attributes ?: return null
        val date = runCatching { LocalDate.parse(attributes.optString("date")) }.getOrNull() ?: return null
        val today = hours(attributes.optJSONArray("today")) ?: return null
        val tomorrow = hours(attributes.optJSONArray("tomorrow")) ?: return null
        val rates = rates(attributes.optJSONObject("rates")) ?: return null
        val ratesTomorrow = rates(attributes.optJSONObject("rates_tomorrow")) ?: rates
        // Every hour must have a price; a schedule that names an unpriced period is not shown at all.
        if (today.any { it !in rates } || tomorrow.any { it !in ratesTomorrow }) return null
        return TouchPowerSchedule(date, today, tomorrow, rates, ratesTomorrow)
    }

    /** `sun.sun`'s next rising and setting, the only daylight times Home Assistant keeps. */
    fun parseSun(state: TouchPowerState?): Pair<Long, Long>? {
        val attributes = state?.attributes ?: return null
        val rising = time(attributes.optString("next_rising")) ?: return null
        val setting = time(attributes.optString("next_setting")) ?: return null
        return rising to setting
    }

    private fun state(json: JSONObject, fallbackId: String?): TouchPowerState? {
        val id = json.optString("entity_id").ifEmpty { fallbackId.orEmpty() }
        if (!isEntityId(id)) return null
        val changed = time(json.optString("last_changed")) ?: time(json.optString("last_updated")) ?: return null
        return TouchPowerState(id, json.optString("state"), changed, json.optJSONObject("attributes"))
    }

    private fun hours(array: JSONArray?): List<TouchPowerPeriod>? {
        if (array == null || array.length() != 24) return null
        val out = ArrayList<TouchPowerPeriod>(24)
        for (i in 0 until 24) out.add(TouchPowerPeriod.of(array.optString(i)) ?: return null)
        return out
    }

    private fun rates(json: JSONObject?): Map<TouchPowerPeriod, Double>? {
        if (json == null) return null
        val out = LinkedHashMap<TouchPowerPeriod, Double>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val period = TouchPowerPeriod.of(key) ?: continue
            val value = json.optDouble(key)
            if (value.isFinite() && value > 0.0 && value < MAX_DOLLARS_PER_KWH) out[period] = value
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private const val MAX_DOLLARS_PER_KWH = 5.0
}

/** Metered use in one clock hour; null when a reading at either end is missing. */
data class TouchPowerHour(val startMillis: Long, val endMillis: Long, val kwh: Double?)

/** Hourly use from the energy meters' readings at the top of each hour. */
object TouchPowerHours {
    const val HOUR_MILLIS = 3_600_000L

    fun hourStart(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()

    /** The 24 hour starts the ring shows, oldest first, ending with the hour now under way. */
    fun starts(nowMillis: Long, zone: ZoneId): List<Long> {
        val current = hourStart(nowMillis, zone)
        return (23 downTo 0).map { current - it * HOUR_MILLIS }
    }

    /** Each hour from its start reading to the next hour's start, the last one up to [current]. */
    fun hourly(starts: List<Long>, readings: Map<Long, Map<String, Double>>, current: Map<String, Double>, nowMillis: Long): List<TouchPowerHour> =
        starts.mapIndexed { index, start ->
            val last = index == starts.lastIndex
            val end = if (last) nowMillis else starts[index + 1]
            TouchPowerHour(start, end, used(readings[start], if (last) current else readings[end]))
        }

    /** Every meter read at both ends, summed. A meter that went backwards was reset, so its new reading is the use. */
    fun used(from: Map<String, Double>?, to: Map<String, Double>?): Double? {
        if (from == null || to == null) return null
        var total = 0.0
        var counted = false
        for ((entity, end) in to) {
            val start = from[entity] ?: continue
            counted = true
            total += if (end >= start) end - start else end
        }
        return if (counted) total else null
    }

    /** Use since local midnight: the ring's hours that began today. */
    fun since(midnightMillis: Long, hours: List<TouchPowerHour>): Double? {
        val today = hours.filter { it.startMillis >= midnightMillis }
        return if (today.none { it.kwh != null }) null else today.sumOf { it.kwh ?: 0.0 }
    }

    fun midnight(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}

/** What the laundry line says about one appliance. */
sealed class TouchLaundryStatus {
    abstract val name: String

    data class Running(override val name: String) : TouchLaundryStatus()

    data class Finished(override val name: String, val atMillis: Long) : TouchLaundryStatus()
}

object TouchLaundry {
    const val SHOW_FINISHED_MILLIS = 12 * 3_600_000L

    /**
     * Running while the sensor is on. Otherwise the latest on-to-off change in the last twelve hours: a restart's
     * unknown-to-off or unavailable-to-off is not a finished load.
     */
    fun status(name: String, current: TouchPowerState?, history: List<TouchPowerState>, nowMillis: Long): TouchLaundryStatus? {
        if (current?.state == "on") return TouchLaundryStatus.Running(name)
        val rows = (history + listOfNotNull(current)).sortedBy { it.lastChangedMillis }
        var finished: Long? = null
        for (i in 1 until rows.size) {
            if (rows[i - 1].state == "on" && rows[i].state == "off") finished = rows[i].lastChangedMillis
        }
        return finished?.takeIf { nowMillis - it in 0..SHOW_FINISHED_MILLIS }?.let { TouchLaundryStatus.Finished(name, it) }
    }
}

/** Sunrun's lifetime meter over the last eight days, read as a week's production and the time of its last report. */
data class TouchPowerSolar(val kwhWeek: Double?, val reportedMillis: Long, val stale: Boolean)

object TouchPowerSolarSummary {
    const val WEEK_MILLIS = 7 * 24 * 3_600_000L
    const val STALE_MILLIS = 36 * 3_600_000L

    fun from(history: List<TouchPowerState>, nowMillis: Long): TouchPowerSolar? {
        val rows = history.filter { it.number != null }.sortedBy { it.lastChangedMillis }
        val latest = rows.lastOrNull() ?: return null
        val value = latest.number ?: return null
        // A Home Assistant restart rewrites the same reading with a new time; the report is when the value last moved.
        var reported = latest.lastChangedMillis
        for (i in rows.indices.reversed()) {
            if (rows[i].number != value) break
            reported = rows[i].lastChangedMillis
        }
        val weekAgo = rows.lastOrNull { it.lastChangedMillis <= reported - WEEK_MILLIS }?.number
        val kwh = weekAgo?.let { value - it }?.takeIf { it >= 0.0 }
        return TouchPowerSolar(kwh, reported, nowMillis - reported > STALE_MILLIS)
    }
}
