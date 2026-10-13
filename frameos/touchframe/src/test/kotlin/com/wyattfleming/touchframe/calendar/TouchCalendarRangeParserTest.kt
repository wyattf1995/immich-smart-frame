package com.wyattfleming.touchframe.calendar

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TouchCalendarRangeParserTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    @Test fun `agenda is seven days and month grid is bounded to forty two`() {
        val date = LocalDate.of(2026, 10, 14)
        assertEquals(7, TouchCalendarRange.forMode(TouchCalendarMode.AGENDA, date).days)
        assertEquals(7, TouchCalendarRange.forMode(TouchCalendarMode.WEEK, date).days)
        assertTrue(TouchCalendarRange.forMode(TouchCalendarMode.MONTH, date).days <= 42)
    }
    @Test fun `all day exclusive end does not select following day`() {
        val range = TouchCalendarRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 8))
        val parsed = TouchCalendarParser().parse("calendar.family", """[{"summary":"Trip","start":{"date":"2026-03-03"},"end":{"date":"2026-03-05"}}]""", range, zone).single()
        assertTrue(parsed.occursOn(LocalDate.of(2026, 3, 4), zone))
        assertFalse(parsed.occursOn(LocalDate.of(2026, 3, 5), zone))
    }
    @Test fun `dst datetime remains an instant and range excludes past end`() {
        val range = TouchCalendarRange(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 15))
        val events = TouchCalendarParser().parse("calendar.family", """[{"summary":"DST","start":{"dateTime":"2026-03-08T09:30:00Z"},"end":{"dateTime":"2026-03-08T10:30:00Z"}},{"summary":"Old","start":{"dateTime":"2026-03-01T10:00:00Z"},"end":{"dateTime":"2026-03-02T10:00:00Z"}}]""", range, zone)
        assertEquals(listOf("DST"), events.map { it.title })
    }
}
