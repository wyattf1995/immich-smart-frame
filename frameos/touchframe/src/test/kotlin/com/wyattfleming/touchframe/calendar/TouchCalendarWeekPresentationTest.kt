package com.wyattfleming.touchframe.calendar

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun boundsSelectedDayDetailsToTwelveEventsAndReportsTheVisibleRange() {
        val events = (1..26).map { index -> event("Event $index", 2026, 3, 9, index % 20, 0) }

        val page = TouchCalendarWeekPresentation.detailsPage(events, requestedPage = 99)

        assertEquals(2, page.page)
        assertEquals(3, page.pageCount)
        assertEquals(26, page.totalEvents)
        assertEquals(25, page.firstEventNumber)
        assertEquals(26, page.lastEventNumber)
        assertEquals(listOf("Event 25", "Event 26"), page.events.map { it.title })
    }

    @Test
    fun selectedDayDetailPagesKeepEveryEventReachable() {
        val events = (1..25).map { index -> event("Event $index", 2026, 3, 9, index % 20, 0) }

        val visible = (0..2).flatMap { page -> TouchCalendarWeekPresentation.detailsPage(events, page).events }

        assertEquals(events, visible)
    }

    @Test
    fun keepsDstTimedEventsOnTheirLocalDayAndFormatsTime() {
        val dstSunday = event("DST meeting", 2026, 3, 8, 10, 0)
        val sundayRange = TouchCalendarRange.forMode(TouchCalendarMode.WEEK, LocalDate.of(2026, 3, 8))

        val sunday = TouchCalendarWeekPresentation.days(sundayRange, listOf(dstSunday), zone, Locale.US).last()

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

    @Test
    fun fittedDetailPagesShowOnlyWholeRowsAndKeepEveryEventReachable() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val day = java.time.LocalDate.of(2026, 10, 17)
        val events = (0 until 14).map { index ->
            TouchCalendarEvent("Event $index", day.atTime(8, 0).plusMinutes(index * 30L).atZone(zone).toInstant().toEpochMilli(), null, false, "calendar.family")
        }
        val first = TouchCalendarWeekPresentation.detailsPage(events, requestedPage = 0, pageSize = 8)
        assertEquals(8, first.events.size)
        assertEquals(2, first.pageCount)
        assertEquals(1, first.firstEventNumber)
        assertEquals(8, first.lastEventNumber)
        val second = TouchCalendarWeekPresentation.detailsPage(events, requestedPage = 1, pageSize = 8)
        assertEquals(events.drop(8), second.events)
        // A larger fitted size never exceeds the verified page cap.
        assertEquals(TouchCalendarWeekPresentation.DETAILS_PER_PAGE, TouchCalendarWeekPresentation.detailsPage(events, 0, pageSize = 40).events.size)
        assertEquals(1, TouchCalendarWeekPresentation.detailsPage(events, 0, pageSize = 0).events.size)
    }
}
