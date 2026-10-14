package com.wyattfleming.touchframe.home

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TouchHomeParser {
    fun parseWeather(body: String): Pair<String, String> {
        val root = JSONObject(body)
        val state = root.optString("state").trim().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Weather state missing")
        val attributes = root.optJSONObject("attributes") ?: JSONObject()
        val temperature = attributes.optNumber("temperature") ?: attributes.optNumber("current_temperature")
        val unit = attributes.optString("temperature_unit").trim()
        val text = temperature?.let { formatTemperature(it, unit) } ?: state
        return state to text
    }

    /**
     * Old Home Assistant releases may omit the entity temperature unit and expose it only through
     * `/api/config`'s unit system. The caller may provide that bounded fallback only for this case.
     */
    fun requiresConfigTemperatureUnit(expectedEntityId: String, body: String): Boolean {
        val root = JSONObject(body)
        require(root.optString("entity_id") == expectedEntityId) { "Unexpected climate entity" }
        val attributes = root.optJSONObject("attributes") ?: JSONObject()
        return attributes.optString("temperature_unit").trim().isEmpty()
    }

    fun parseConfigTemperatureUnit(body: String): String? =
        recognizedTemperatureUnit(JSONObject(body).optJSONObject("unit_system")?.optString("temperature"))

    /**
     * A state can always be displayed when it reports a recognized unit. Commands remain disabled
     * unless the state explicitly advertises the corresponding capability and safe inputs.
     */
    fun parseClimate(expectedEntityId: String, body: String, fallbackTemperatureUnit: String? = null): TouchClimate? {
        val root = JSONObject(body)
        require(root.optString("entity_id") == expectedEntityId) { "Unexpected climate entity" }
        val attributes = root.optJSONObject("attributes") ?: JSONObject()
        val modes = attributes.optJSONArray("hvac_modes")?.strings(MAX_MODES).orEmpty()
        val mode = root.optString("state").trim().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Climate mode missing")
        val attributeUnit = attributes.optString("temperature_unit").trim()
        val unit = (recognizedTemperatureUnit(attributeUnit) ?: if (attributeUnit.isEmpty()) {
            recognizedTemperatureUnit(fallbackTemperatureUnit)
        } else null) ?: return null
        val minimum = attributes.optNumber("min_temp")
        val maximum = attributes.optNumber("max_temp")
        val step = attributes.optNumber("target_temp_step")
        val boundsAreValid = minimum != null && maximum != null && minimum <= maximum
        val stepIsValid = step == null || step > 0.0
        val supportedFeatures = attributes.optLong("supported_features")
        val target = attributes.optNumber("temperature")
        val low = attributes.optNumber("target_temp_low")
        val high = attributes.optNumber("target_temp_high")
        val rangeIsValid = low != null && high != null && low <= high
        val canSetTarget = supportedFeatures != null && (supportedFeatures and TARGET_TEMPERATURE_FEATURE) != 0L &&
            target != null && boundsAreValid && stepIsValid && !rangeIsValid && mode != "heat_cool"
        return TouchClimate(
            entityId = expectedEntityId,
            currentTemperature = attributes.optNumber("current_temperature"),
            targetTemperature = target,
            targetTemperatureLow = if (rangeIsValid) low else null,
            targetTemperatureHigh = if (rangeIsValid) high else null,
            unit = unit,
            mode = mode,
            modes = modes,
            minTemperature = if (boundsAreValid) minimum else null,
            maxTemperature = if (boundsAreValid) maximum else null,
            step = step.takeIf { stepIsValid },
            canSetTarget = canSetTarget,
            canSetMode = modes.isNotEmpty() && mode in modes,
        )
    }

    fun parseCalendarEvents(
        calendarId: String,
        body: String,
        now: Instant,
        zoneId: ZoneId,
    ): List<TouchCalendarEvent> {
        require(CALENDAR_ENTITY.matches(calendarId)) { "Unexpected calendar entity" }
        val input = JSONArray(body)
        return buildList {
            for (index in 0 until minOf(input.length(), MAX_EVENTS_PER_CALENDAR)) {
                val event = input.optJSONObject(index) ?: continue
                val start = parseCalendarTime(event.optJSONObject("start"), zoneId) ?: continue
                val end = parseCalendarTime(event.optJSONObject("end"), zoneId)
                val effectiveEnd = end?.first ?: start.first
                if (effectiveEnd.isBefore(start.first) || effectiveEnd.isBefore(now)) continue
                val title = event.optString("summary").trim().takeIf(String::isNotBlank) ?: continue
                add(TouchCalendarEvent(title, start.first.toEpochMilli(), end?.first?.toEpochMilli(), start.second))
            }
        }.sortedBy(TouchCalendarEvent::startEpochMillis)
    }

    private fun parseCalendarTime(value: JSONObject?, zoneId: ZoneId): Pair<Instant, Boolean>? {
        value ?: return null
        value.optString("dateTime").trim().takeIf(String::isNotBlank)?.let { raw ->
            return runCatching { Instant.parse(raw) }.getOrElse {
                runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull()
            }?.let { it to false }
        }
        value.optString("date").trim().takeIf(String::isNotBlank)?.let { raw ->
            return runCatching { LocalDate.parse(raw).atStartOfDay(zoneId).toInstant() }.getOrNull()?.let { it to true }
        }
        return null
    }

    private fun JSONObject.optNumber(key: String): Double? = when (val value = opt(key)) {
        is Number -> value.toDouble().takeIf(Double::isFinite)
        is String -> value.toDoubleOrNull()?.takeIf(Double::isFinite)
        else -> null
    }

    private fun JSONObject.optLong(key: String): Long? = when (val value = opt(key)) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        is Float, is Double -> value.toDouble().takeIf { it.isFinite() && it == it.toLong().toDouble() }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun recognizedTemperatureUnit(value: String?): String? = when (value?.trim()) {
        "°C", "C" -> "°C"
        "°F", "F" -> "°F"
        else -> null
    }

    private fun JSONArray.strings(max: Int): List<String> = buildList {
        for (index in 0 until minOf(length(), max)) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }.distinct()

    private fun formatTemperature(value: Double, unit: String): String =
        "${if (value == value.toLong().toDouble()) value.toLong() else value}$unit"

    private companion object {
        const val MAX_MODES = 16
        const val MAX_EVENTS_PER_CALENDAR = 200
        const val TARGET_TEMPERATURE_FEATURE = 1L
        val CALENDAR_ENTITY = Regex("calendar\\.[a-z0-9_]+")
    }
}
