package com.wyattfleming.touchframe.calendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Parses only the HA Calendar range response; it never interprets Home's seven-day snapshot. */
class TouchCalendarParser {
    fun parse(calendarId: String, body: String, range: TouchCalendarRange, zoneId: ZoneId): List<TouchCalendarEvent> {
        require(CALENDAR_ID.matches(calendarId))
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) { "Calendar response exceeds bound" }
        val array = JSONArray(body)
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_EVENTS_PER_CALENDAR)) {
                val item = array.optJSONObject(index) ?: continue
                val start = timestamp(item.optJSONObject("start"), zoneId) ?: continue
                val end = timestamp(item.optJSONObject("end"), zoneId)
                val effectiveEnd = end?.instant ?: start.instant
                if (effectiveEnd.isBefore(start.instant)) continue
                val title = item.optString("summary").trim().takeIf { !it.isNullOrBlank() }?.take(TouchCalendarEvent.MAX_TITLE_LENGTH) ?: continue
                // HA's end is exclusive for all-day entries. Instant overlap includes events crossing this range.
                if (effectiveEnd < range.start.atStartOfDay(zoneId).toInstant() || start.instant >= range.endExclusive.atStartOfDay(zoneId).toInstant()) continue
                add(TouchCalendarEvent(title, start.instant.toEpochMilli(), end?.instant?.toEpochMilli(), start.allDay, calendarId))
            }
        }.sortedBy(TouchCalendarEvent::startEpochMillis)
    }
    private fun timestamp(value: JSONObject?, zoneId: ZoneId): Timestamp? {
        value ?: return null
        value.optString("dateTime").trim().takeIf(String::isNotBlank)?.let { raw ->
            val instant = runCatching { Instant.parse(raw) }.getOrElse { runCatching { ZonedDateTime.parse(raw).toInstant() }.getOrNull() } ?: return null
            return Timestamp(instant, false)
        }
        value.optString("date").trim().takeIf(String::isNotBlank)?.let { raw ->
            return runCatching { LocalDate.parse(raw).atStartOfDay(zoneId).toInstant() }.getOrNull()?.let { Timestamp(it, true) }
        }
        return null
    }
    private data class Timestamp(val instant: Instant, val allDay: Boolean)
    private companion object {
        val CALENDAR_ID = Regex("calendar\\.[a-z0-9_]+")
        const val MAX_EVENTS_PER_CALENDAR = 200
        const val MAX_RESPONSE_BYTES = 512 * 1024
    }
}
