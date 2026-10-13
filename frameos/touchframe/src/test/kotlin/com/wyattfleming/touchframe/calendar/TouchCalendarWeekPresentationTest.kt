package com.wyattfleming.touchframe.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchCalendarWeekPresentationTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val range = TouchCalendarRange.forMode(TouchCalendarMode.WEEK, LocalDate.of(2026, 3, 10))

    @Test
    fun startsInWeekModeForTouchCalendar() {
        assertEquals(TouchCalendarMode.WEEK, TouchCalendarWeekPresentation.DEFAULT_MODE)
    }

    @Test
    fun groupsSevenDaysWithAllDayFirstAndBoundedPreview() {
        val events = listOf(
            event("Later timed", 2026, 3, 9, 15, 0),
            event("All day", 2026, 3, 9, 23, 0, allDay = true, endDay = 10),
            event("Earlier timed", 2026, 3, 9, 9, 0),
            event("Fourth timed", 2026, 3, 9, 16, 0),
            event("Fifth timed", 2026, 3, 9, 17, 0),
        )

        val monday = TouchCalendarWeekPresentation.days(range, events, zone).first()

        assertEquals(7, TouchCalendarWeekPresentation.days(range, events, zone).size)
        assertEquals(listOf("All day", "Earlier timed", "Later timed"), monday.preview.map { it.event.title })
        assertEquals(2, monday.overflowCount)
        assertEquals(listOf("All day", "Earlier timed", "Later timed", "Fourth timed", "Fifth timed"), monday.events.map { it.title })
    }

    @Test
    fun honorsExclusiveEndForMultiDayAllDayEvents() {
        val event = event("Two day trip", 2026, 3, 9, 0, 0, allDay = true, endDay = 11)

        val days = TouchCalendarWeekPresentation.days(range, listOf(event), zone)

        assertEquals(listOf(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 10)), days.filter { it.events.isNotEmpty() }.map { it.date })
    }

    @Test
    fun keepsDstTimedEventsOnTheirLocalDayAndFormatsTime() {
        val dstSunday = event("DST meeting", 2026, 3, 8, 10, 0)
        val sundayRange = TouchCalendarRange.forMode(TouchCalendarMode.WEEK, LocalDate.of(2026, 3, 8))

        val sunday = TouchCalendarWeekPresentation.days(sundayRange, listOf(dstSunday), zone).last()

        assertEquals(LocalDate.of(2026, 3, 8), sunday.date)
        assertEquals("10:00 AM", sunday.preview.single().timeLabel)
        assertTrue(sunday.preview.single().isTimed)
    }

    private fun event(
        title: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        allDay: Boolean = false,
        endDay: Int? = null,
    ): TouchCalendarEvent {
        val start = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
        val end = endDay?.let { ZonedDateTime.of(year, month, it, 0, 0, 0, 0, zone).toInstant().toEpochMilli() }
        return TouchCalendarEvent(title, start.toInstant().toEpochMilli(), end, allDay, "primary")
    }
}
