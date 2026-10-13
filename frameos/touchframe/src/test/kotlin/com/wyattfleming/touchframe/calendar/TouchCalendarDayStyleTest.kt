package com.wyattfleming.touchframe.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TouchCalendarDayStyleTest {
    private val today = LocalDate.of(2026, 10, 13)

    @Test
    fun `month cells separate past days, today and upcoming days`() {
        assertEquals(TouchCalendarDayTone.TODAY, TouchCalendarDayStyle.toneFor(today, today, inMonth = true))
        assertEquals(TouchCalendarDayTone.PAST, TouchCalendarDayStyle.toneFor(today.minusDays(1), today, inMonth = true))
        assertEquals(TouchCalendarDayTone.UPCOMING, TouchCalendarDayStyle.toneFor(today.plusDays(1), today, inMonth = true))
    }

    @Test
    fun `days outside the month are past when earlier and quiet placeholders when later`() {
        assertEquals(TouchCalendarDayTone.PAST, TouchCalendarDayStyle.toneFor(LocalDate.of(2026, 9, 28), today, inMonth = false))
        assertEquals(TouchCalendarDayTone.OUTSIDE_MONTH, TouchCalendarDayStyle.toneFor(LocalDate.of(2026, 11, 1), today, inMonth = false))
        // Browsing next month while today is outside it: the grid's earlier days are still past.
        assertEquals(TouchCalendarDayTone.PAST, TouchCalendarDayStyle.toneFor(LocalDate.of(2026, 10, 5), today, inMonth = false))
    }
}
