package com.wyattfleming.touchframe.ocean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TouchOceanGlanceTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val conditions = TouchOceanParser().parse(TouchOceanFixtures.body())!!

    @Test
    fun `Home's ocean glance names the spot, its water and the next tide`() {
        val glance = TouchOceanGlance.from(conditions, TouchOceanFixtures.GENERATED.toEpochMilli(), la, twentyFourHour = false)
        assertEquals("Main Beach", glance.place)
        assertEquals(conditions.waterC?.let(TouchOceanUnits::fahrenheit), glance.waterF)
        // 07:17Z is 12:17 AM in Laguna Beach; the 00:52Z low already passed.
        assertEquals("High tide 12:17 AM", glance.nextTide)
    }

    @Test
    fun `with every listed tide in the past the glance says nothing about tides`() {
        val later = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
        assertNull(TouchOceanGlance.from(conditions, later, la, twentyFourHour = false).nextTide)
    }
}
