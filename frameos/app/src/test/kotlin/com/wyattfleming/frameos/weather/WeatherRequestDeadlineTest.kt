package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRequestDeadlineTest {
    @Test
    fun `injected monotonic clock preserves the exact request deadline`() {
        var now = 0L
        val deadline = WeatherRequestDeadline(startNanos = 0L, timeoutMillis = 250L, clockNanos = { now })
        org.junit.Assert.assertEquals(250L, deadline.remainingMillis())
        now = 249_000_000L
        org.junit.Assert.assertEquals(1L, deadline.remainingMillis())
        org.junit.Assert.assertFalse(deadline.isExpired())
        now = 250_000_000L
        org.junit.Assert.assertEquals(0L, deadline.remainingMillis())
        org.junit.Assert.assertTrue(deadline.isExpired())
    }

    @Test
    fun `shares one total deadline across current daily and hourly requests`() {
        val deadline = WeatherRequestDeadline(startNanos = 1_000_000L, timeoutMillis = 2_000L)

        assertEquals(2_000L, deadline.remainingMillis(nowNanos = 1_000_000L))
        assertEquals(1L, deadline.remainingMillis(nowNanos = 2_000_999_999L))
        assertTrue(deadline.isExpired(nowNanos = 2_001_000_000L))
    }
}
