package com.wyattfleming.touchframe.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TouchHomeAgendaTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = at(2026, 10, 13, 10, 42)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun day(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `every upcoming time is shown under its own day`() {
        val events = listOf(
            TouchCalendarEvent("Piano lesson", at(2026, 10, 13, 15, 15), null, false),
            TouchCalendarEvent("Dentist", at(2026, 10, 14, 9, 0), null, false),
            TouchCalendarEvent("In-service day", day(2026, 10, 16), day(2026, 10, 17), true),
        )
        val plan = TouchHomeAgenda.plan(events, now, zone, maxLines = 20, locale = Locale.US)
        val sections = plan.sections

        assertEquals(0, plan.hiddenCount)
        assertEquals(listOf("Today", "Tomorrow", "Friday"), sections.map { it.label })
        assertEquals("3:15 PM", sections[0].rows.single().timeLabel)
        assertTrue(sections[0].isToday)
        assertEquals("9:00 AM", sections[1].rows.single().timeLabel)
        assertEquals("All day", sections[2].rows.single().timeLabel)
    }

    @Test
    fun `events already under way are listed today and marked now`() {
        val events = listOf(
            TouchCalendarEvent("Conference", at(2026, 10, 12, 9, 0), at(2026, 10, 14, 17, 0), false),
            TouchCalendarEvent("Later today", at(2026, 10, 13, 18, 0), null, false),
        )
        val sections = TouchHomeAgenda.plan(events, now, zone, maxLines = 20, locale = Locale.US).sections

        assertEquals(1, sections.size)
        assertEquals("Today", sections.single().label)
        assertEquals(listOf("Now", "6:00 PM"), sections.single().rows.map { it.timeLabel })
    }

    @Test
    fun `events that do not fit are counted, never silently dropped`() {
        val events = (0 until 8).map { TouchCalendarEvent("Event $it", at(2026, 10, 14 + it / 3, 9 + it, 0), null, false) }
        val plan = TouchHomeAgenda.plan(events.shuffled(java.util.Random(4)), now, zone, maxLines = 6, locale = Locale.US)

        // One day label + three events, with a line left for "5 more events"; a label never appears without an event.
        assertEquals(listOf("Tomorrow"), plan.sections.map { it.label })
        assertEquals(listOf("Event 0", "Event 1", "Event 2"), plan.sections.flatMap { it.rows }.map { it.title })
        assertEquals(5, plan.hiddenCount)
        assertEquals(8, plan.hiddenCount + plan.sections.sumOf { it.rows.size })
    }

    @Test
    fun `the overflow line is only reserved while events remain`() {
        val events = listOf(
            TouchCalendarEvent("First", at(2026, 10, 13, 12, 0), null, false),
            TouchCalendarEvent("Second", at(2026, 10, 13, 13, 0), null, false),
        )
        val exact = TouchHomeAgenda.plan(events, now, zone, maxLines = 3, locale = Locale.US)
        assertEquals(0, exact.hiddenCount)
        assertEquals(2, exact.sections.single().rows.size)

        val three = events + TouchCalendarEvent("Third", at(2026, 10, 13, 14, 0), null, false)
        val short = TouchHomeAgenda.plan(three, now, zone, maxLines = 3, locale = Locale.US)
        assertEquals(2, short.hiddenCount)
        assertEquals(listOf("First"), short.sections.single().rows.map { it.title })
        // Too small for any event: only the overflow line ("3 more events") is shown.
        val none = TouchHomeAgenda.plan(three, now, zone, maxLines = 1, locale = Locale.US)
        assertTrue(none.sections.isEmpty())
        assertEquals(3, none.hiddenCount)
    }

    @Test
    fun `day labels use weekday names within a week and dates beyond it`() {
        val today = LocalDate.of(2026, 10, 13)
        assertEquals("Sunday", TouchHomeAgenda.dayLabel(LocalDate.of(2026, 10, 18), today, Locale.US))
        assertEquals("Tuesday, October 20", TouchHomeAgenda.dayLabel(LocalDate.of(2026, 10, 20), today, Locale.US))
    }

    @Test
    fun `weather glance uses human condition names and never a raw machine state`() {
        assertEquals("Partly cloudy", TouchWeatherLabel.condition("partlycloudy"))
        assertEquals("Clear night", TouchWeatherLabel.condition("clear-night"))
        assertEquals("Rain and thunderstorms", TouchWeatherLabel.condition("lightning-rainy"))
        assertEquals("Sunny", TouchWeatherLabel.condition("Sunny"))
        assertEquals("Smoke haze", TouchWeatherLabel.condition("smoke-haze"))
        assertNull(TouchWeatherLabel.condition("unavailable"))
        assertNull(TouchWeatherLabel.condition("  "))
        assertEquals(TouchWeatherLabel.Glyph.MOON, TouchWeatherLabel.glyph("clear-night"))
        assertEquals(TouchWeatherLabel.Glyph.NONE, TouchWeatherLabel.glyph("unavailable"))
    }

    private fun climate(target: Double?, entity: String = "climate.hall", step: Double? = 1.0) = TouchClimate(
        entityId = entity, currentTemperature = 71.0, targetTemperature = target, unit = "°F", mode = "heat",
        modes = listOf("heat", "off"), minTemperature = 50.0, maxTemperature = 90.0, step = step,
        canSetTarget = true, canSetMode = true,
    )

    @Test
    fun `a confirmed setpoint clears the draft so later refreshes show the real value`() {
        val draft = TouchClimateDraft()
        draft.onRefreshed(null, climate(72.0))
        assertEquals(73.0, draft.step(climate(72.0), 1)!!, 0.0)
        assertTrue(draft.dirty)

        // Home Assistant clamped the request; the refreshed value differs from the draft.
        draft.onRefreshed("climate.hall", climate(72.5))
        assertTrue(draft.dirty)
        draft.onCommandFinished(confirmed = true, climate = climate(72.5))
        assertFalse(draft.dirty)
        assertEquals(72.5, draft.target!!, 0.0)

        // Someone changes it from another app: Home follows.
        draft.onRefreshed("climate.hall", climate(68.0))
        assertEquals(68.0, draft.target!!, 0.0)
    }

    @Test
    fun `a refresh that already matches the draft settles it and a failed command keeps it`() {
        val draft = TouchClimateDraft()
        draft.onRefreshed(null, climate(72.0))
        draft.step(climate(72.0), 1)
        draft.onCommandFinished(confirmed = false, climate = climate(72.0))
        assertTrue(draft.dirty)
        assertEquals(73.0, draft.target!!, 0.0)

        draft.onRefreshed("climate.hall", climate(73.0))
        assertFalse(draft.dirty)
    }

    @Test
    fun `stepping back to the setpoint is not a pending change and steps stay in range`() {
        val draft = TouchClimateDraft()
        draft.onRefreshed(null, climate(72.0))
        draft.step(climate(72.0), 1)
        draft.step(climate(72.0), -1)
        assertFalse(draft.dirty)
        val atMax = climate(90.0)
        draft.onRefreshed("climate.hall", atMax)
        assertEquals(90.0, draft.step(atMax, 1)!!, 0.0)
        assertFalse(draft.dirty)
        val celsius = climate(21.0, step = null).copy(unit = "°C", minTemperature = 7.0, maxTemperature = 30.0)
        draft.onRefreshed("climate.hall", celsius)
        assertEquals(21.5, draft.step(celsius, 1)!!, 0.0)
    }

    @Test
    fun `a different thermostat entity discards any draft`() {
        val draft = TouchClimateDraft()
        draft.onRefreshed(null, climate(72.0))
        draft.step(climate(72.0), 1)
        draft.onRefreshed("climate.hall", climate(65.0, entity = "climate.bedroom"))
        assertFalse(draft.dirty)
        assertEquals(65.0, draft.target!!, 0.0)
    }
}
