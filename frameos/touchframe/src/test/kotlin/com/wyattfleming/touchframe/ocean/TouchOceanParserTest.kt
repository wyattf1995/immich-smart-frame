package com.wyattfleming.touchframe.ocean

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchOceanParserTest {
    private val parser = TouchOceanParser()

    @Test
    fun `reads the beach, the current readings, sun times and the tide`() {
        val parsed = requireNotNull(parser.parse(TouchOceanFixtures.body()))
        assertEquals("main-beach", parsed.spotId)
        assertEquals("Main Beach", parsed.spotName)
        assertEquals("Laguna Beach, CA", parsed.place)
        assertEquals("America/Los_Angeles", parsed.timeZone)
        assertEquals(22.0, parsed.waterC!!, 0.0)
        assertEquals(1.11, parsed.waveM!!, 0.0)
        assertEquals(352.0, parsed.windFromDeg!!, 0.0)
        assertEquals(2, parsed.days.size)
        assertEquals("Newport Bay Entrance, Corona del Mar", parsed.tideStation)
        assertEquals(listOf(false, true, false, true, false), parsed.tides.map { it.high })
        assertTrue(parsed.tideCurve.size > 400)
        assertEquals(parsed.tideCurve.sortedBy { it.epochMillis }, parsed.tideCurve)
    }

    @Test
    fun `missing or null readings stay unknown instead of becoming zero`() {
        val current = TouchOceanFixtures.current().put("waterTemperatureC", JSONObject.NULL)
        current.remove("windGustsKmh")
        val parsed = requireNotNull(parser.parse(TouchOceanFixtures.body(current = current)))
        assertNull(parsed.waterC)
        assertNull(parsed.gustKmh)
        assertEquals(16.8, parsed.windKmh!!, 0.0)
    }

    @Test
    fun `an unknown schema, a malformed body or an oversized body is refused`() {
        assertNull(parser.parse(TouchOceanFixtures.body(schemaVersion = 2)))
        assertNull(parser.parse("{\"schemaVersion\":1"))
        assertNull(parser.parse("x".repeat(TouchOceanParser.MAX_BODY_CHARS + 1)))
    }

    @Test
    fun `tide events of an unknown type are skipped, not guessed`() {
        val parsed = requireNotNull(parser.parse(TouchOceanFixtures.body(extraTides = listOf(TouchOceanFixtures.tide("2026-10-15T08:00:00Z", 1.0, "slack")))))
        assertEquals(5, parsed.tides.size)
    }
}
