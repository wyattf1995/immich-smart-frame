package com.wyattfleming.touchframe.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TouchCalendarParserBoundsTest {
    private val range = TouchCalendarRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
    @Test fun `parser caps each calendar before rendering`() {
        val body = (0 until 205).joinToString(prefix = "[", postfix = "]") { index ->
            """{"summary":"Event $index","start":{"date":"2026-10-14"},"end":{"date":"2026-10-15"}}"""
        }
        assertEquals(200, TouchCalendarParser().parse("calendar.family", body, range, ZoneOffset.UTC).size)
    }
    @Test fun `ending at the requested range start excludes a duration event`() {
        val body = """[{"summary":"Finished","start":{"date":"2026-09-30"},"end":{"date":"2026-10-01"}}]"""
        assertTrue(TouchCalendarParser().parse("calendar.family", body, range, ZoneOffset.UTC).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class)
    fun `oversized payload is rejected before JSON parsing`() {
        TouchCalendarParser().parse("calendar.family", " ".repeat(512 * 1024 + 1), range, ZoneOffset.UTC)
    }
}
