package com.wyattfleming.touchframe.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TouchCalendarTodayColumnTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 10, 13)
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 13, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun event(title: String, start: Long, end: Long?) = TouchCalendarEvent(title, start, end, false, "calendar.family")

    private val events = listOf(
        event("Story time", at(11, 30), at(12, 15)),
        event("Piano", at(15, 15), at(16, 0)),
        event("Book club", at(19, 0), at(21, 0)),
        event("Call", at(20, 30), at(21, 0)),
    )
    private val day = TouchCalendarWeekPresentation.days(
        TouchCalendarRange.forMode(TouchCalendarMode.WEEK, today), events, zone, Locale.US,
    ).single { it.date == today }

    @Test
    fun `finished events collapse into a count and the current one reads Now`() {
        val column = TouchCalendarWeekPresentation.todayColumn(day, at(20, 12), zone, Locale.US)
        assertEquals(2, column.earlierCount)
        assertEquals(listOf("Now", "8:30 PM"), column.preview.map { it.timeLabel })
        assertEquals(listOf("Book club", "Call"), column.preview.map { it.event.title })
        assertEquals(0, column.overflowCount)
    }

    @Test
    fun `before anything starts the column is the ordinary preview`() {
        val column = TouchCalendarWeekPresentation.todayColumn(day, at(10, 42), zone, Locale.US)
        assertEquals(0, column.earlierCount)
        assertEquals(day.preview.map { it.timeLabel }, column.preview.map { it.timeLabel })
        assertEquals(day.overflowCount, column.overflowCount)
    }

    @Test
    fun `timing treats all-day events as current all day and open-ended ones as finished once started`() {
        val allDay = TouchCalendarEvent("Holiday", today.atStartOfDay(zone).toInstant().toEpochMilli(), null, true, "calendar.family")
        assertEquals(TouchCalendarWeekPresentation.EventTiming.UPCOMING, TouchCalendarWeekPresentation.timing(allDay, at(20, 0)))
        assertEquals(TouchCalendarWeekPresentation.EventTiming.ENDED, TouchCalendarWeekPresentation.timing(event("Reminder", at(9, 0), null), at(9, 1)))
        assertEquals(TouchCalendarWeekPresentation.EventTiming.NOW, TouchCalendarWeekPresentation.timing(events[2], at(19, 30)))
    }
}
