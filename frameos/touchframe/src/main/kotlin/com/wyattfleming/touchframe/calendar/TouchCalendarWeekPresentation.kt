package com.wyattfleming.touchframe.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure, bounded Week presentation. It reuses [TouchCalendarEvent.occursOn] so all-day
 * and midnight-exclusive ends have exactly the same meaning in every calendar surface.
 */
object TouchCalendarWeekPresentation {
    const val PREVIEW_PER_DAY = 3
    val DEFAULT_MODE: TouchCalendarMode = TouchCalendarMode.WEEK

    data class EventPreview(
        val event: TouchCalendarEvent,
        val timeLabel: String,
        val isTimed: Boolean,
    )

    data class Day(
        val date: LocalDate,
        val events: List<TouchCalendarEvent>,
        val preview: List<EventPreview>,
        val overflowCount: Int,
    )

    fun days(
        range: TouchCalendarRange,
        events: List<TouchCalendarEvent>,
        zoneId: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): List<Day> {
        require(range.days == WEEK_DAYS)
        return (0 until WEEK_DAYS.toInt()).map { index ->
            val date = range.start.plusDays(index.toLong())
            val matching = events
                .asSequence()
                .filter { it.occursOn(date, zoneId) }
                .sortedWith(EVENT_ORDER)
                .toList()
            Day(
                date = date,
                events = matching,
                preview = matching.take(PREVIEW_PER_DAY).map { previewFor(it, zoneId, locale) },
                overflowCount = (matching.size - PREVIEW_PER_DAY).coerceAtLeast(0),
            )
        }
    }

    private fun previewFor(event: TouchCalendarEvent, zoneId: ZoneId, locale: Locale): EventPreview =
        if (event.allDay) EventPreview(event, "All day", false)
        else EventPreview(
            event,
            Instant.ofEpochMilli(event.startEpochMillis)
                .atZone(zoneId)
                .format(DateTimeFormatter.ofPattern("h:mm a", locale)),
            true,
        )

    private val EVENT_ORDER = compareBy<TouchCalendarEvent>(
        { if (it.allDay) 0 else 1 },
        { it.startEpochMillis },
        { it.title },
        { it.calendarId },
    )
    private const val WEEK_DAYS = 7L
}
