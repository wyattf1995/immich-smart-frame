package com.wyattfleming.touchframe.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class TouchHomeParserTest {
    private val parser = TouchHomeParser()

    @Test
    fun `climate parser keeps valid card when target step is absent`() {
        val climate = parser.parseClimate(
            "climate.entryway_entryway",
            """{"entity_id":"climate.entryway_entryway","state":"heat","attributes":{"temperature":71,"temperature_unit":"°F","supported_features":1,"min_temp":50,"max_temp":85}}""",
        )
        assertEquals(null, climate?.step)
        assertEquals(50.0, climate?.minTemperature)
        assertEquals(85.0, climate?.maxTemperature)
        assertTrue(climate?.canSetTarget == true)
    }

    @Test
    fun `climate parser uses only a recognized config unit when old HA omits the entity unit`() {
        val state = """{"entity_id":"climate.entryway_entryway","state":"heat","attributes":{"current_temperature":70,"temperature":71,"supported_features":1,"hvac_modes":["off","heat"],"min_temp":50,"max_temp":85,"target_temp_step":1}}"""

        assertTrue(parser.requiresConfigTemperatureUnit("climate.entryway_entryway", state))
        assertEquals("°F", parser.parseConfigTemperatureUnit("""{"unit_system":{"temperature":"°F"}}"""))
        val climate = parser.parseClimate("climate.entryway_entryway", state, fallbackTemperatureUnit = "°F")!!

        assertEquals("°F", climate.unit)
        assertTrue(climate.canSetTarget)
        assertTrue(climate.canSetMode)
    }

    @Test
    fun `climate parser keeps a reported range read only without single target capability`() {
        val climate = parser.parseClimate(
            "climate.entryway_entryway",
            """{"entity_id":"climate.entryway_entryway","state":"heat_cool","attributes":{"current_temperature":70,"target_temp_low":68,"target_temp_high":75,"temperature_unit":"°F","supported_features":2,"hvac_modes":["off","heat_cool"],"min_temp":50,"max_temp":85}}""",
        )!!

        assertEquals(68.0, climate.targetTemperatureLow)
        assertEquals(75.0, climate.targetTemperatureHigh)
        assertTrue(!climate.canSetTarget)
        assertTrue(climate.canSetMode)
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
        assertEquals(Instant.parse("2026-10-14T20:00:00Z").toEpochMilli(), events.single().startEpochMillis)
        assertEquals(Instant.parse("2026-10-14T21:00:00Z").toEpochMilli(), events.single().endEpochMillis)
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
