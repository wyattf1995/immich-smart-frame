package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherRequestDeadlineTest {
    @Test
    fun `shares one total deadline across current daily and hourly requests`() {
        val deadline = WeatherRequestDeadline(startNanos = 1_000_000L, timeoutMillis = 2_000L)

        assertEquals(2_000L, deadline.remainingMillis(nowNanos = 1_000_000L))
        assertEquals(1L, deadline.remainingMillis(nowNanos = 2_000_999_999L))
        assertTrue(deadline.isExpired(nowNanos = 2_001_000_000L))
    }
}
