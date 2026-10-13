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

    /** Missing or malformed bounds produce no controllable climate card. */
    fun parseClimate(expectedEntityId: String, body: String): TouchClimate? {
        val root = JSONObject(body)
        require(root.optString("entity_id") == expectedEntityId) { "Unexpected climate entity" }
        val attributes = root.optJSONObject("attributes") ?: JSONObject()
        val modes = attributes.optJSONArray("hvac_modes")?.strings(MAX_MODES).orEmpty()
        val mode = root.optString("state").trim().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Climate mode missing")
        val unit = attributes.optString("temperature_unit").trim().takeIf(String::isNotBlank) ?: return null
        val minimum = attributes.optNumber("min_temp") ?: return null
        val maximum = attributes.optNumber("max_temp") ?: return null
        val step = attributes.optNumber("target_temp_step") ?: return null
        if (minimum > maximum || step <= 0.0) return null
        return TouchClimate(
            entityId = expectedEntityId,
            currentTemperature = attributes.optNumber("current_temperature"),
            targetTemperature = attributes.optNumber("temperature"),
            unit = unit,
            mode = mode,
            modes = modes,
            minTemperature = minimum,
            maxTemperature = maximum,
            step = step,
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
                val effectiveEnd = end ?: start.first
                if (effectiveEnd.isBefore(now)) continue
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
        val CALENDAR_ENTITY = Regex("calendar\\.[a-z0-9_]+")
    }
}
