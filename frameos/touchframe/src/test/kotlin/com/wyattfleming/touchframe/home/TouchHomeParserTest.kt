package com.wyattfleming.touchframe.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TouchHomeParserTest {
    private val parser = TouchHomeParser()

    @Test
    fun `climate parser with missing control capability suppresses the controllable card`() {
        val climate = parser.parseClimate(
            "climate.entryway_entryway",
            """{"entity_id":"climate.entryway_entryway","state":"heat","attributes":{"temperature_unit":"°F","min_temp":50,"max_temp":85}}""",
        )
        org.junit.Assert.assertNull(climate)
    }

    @Test
    fun `calendar parser handles all-day and date-time events then excludes past events`() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val events = parser.parseCalendarEvents(
            "calendar.family",
            """[
              {"summary":"Past","start":{"dateTime":"2026-10-11T10:00:00Z"},"end":{"dateTime":"2026-10-11T11:00:00Z"}},
              {"summary":"All day","start":{"date":"2026-10-13"},"end":{"date":"2026-10-14"}},
              {"summary":"Meeting","start":{"dateTime":"2026-10-12T13:00:00Z"},"end":{"dateTime":"2026-10-12T14:00:00Z"}}
            ]""",
            now,
            ZoneOffset.UTC,
        )
        assertEquals(listOf("Meeting", "All day"), events.map { it.title })
        assertEquals(listOf(false, true), events.map { it.allDay })
    }

    @Test
    fun `calendar parser skips invalid time order and accepts offset date-time`() {
        val events = parser.parseCalendarEvents(
            "calendar.family",
            """[
              {"summary":"Invalid","start":{"dateTime":"2026-10-14T14:00:00-07:00"},"end":{"dateTime":"2026-10-14T13:00:00-07:00"}},
              {"summary":"Offset","start":{"dateTime":"2026-10-14T13:00:00-07:00"},"end":{"dateTime":"2026-10-14T14:00:00-07:00"}}
            ]""",
            Instant.parse("2026-10-12T00:00:00Z"),
            ZoneOffset.UTC,
        )
        assertEquals(listOf("Offset"), events.map { it.title })
        assertEquals(Instant.parse("2026-10-14T20:00:00Z").toEpochMilli(), events.single().endEpochMillis)
    }

    @Test
    fun `calendar parser bounds malformed and excessive records`() {
        val body = buildString {
            append('[')
            repeat(205) { index -> append("{\"summary\":\"E$index\",\"start\":{\"date\":\"2026-10-13\"}},") }
            append("{\"summary\":\"bad\",\"start\":{\"date\":\"not-a-date\"}}]")
        }
        val events = parser.parseCalendarEvents("calendar.family", body, Instant.parse("2026-10-12T00:00:00Z"), ZoneOffset.UTC)
        assertEquals(200, events.size)
        assertTrue(events.all { it.title.startsWith("E") })
    }
}
