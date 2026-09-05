package com.wyattfleming.frameos.config

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuietHoursRefreshTest {
    private val quiet = QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), 10)

    @Test fun `disabled quiet hours create no wakeup`() {
        assertNull(QuietHoursBrightnessPolicy.nextRefreshDelayMillis(null, LocalTime.NOON))
    }

    @Test fun `refresh arrives at both overnight boundaries`() {
        assertEquals(10_000L, QuietHoursBrightnessPolicy.nextRefreshDelayMillis(quiet, LocalTime.of(21, 59, 50)))
        assertEquals(5_000L, QuietHoursBrightnessPolicy.nextRefreshDelayMillis(quiet, LocalTime.of(6, 59, 55)))
        assertEquals(60_000L, QuietHoursBrightnessPolicy.nextRefreshDelayMillis(quiet, LocalTime.of(22, 0)))
        assertEquals(60_000L, QuietHoursBrightnessPolicy.nextRefreshDelayMillis(quiet, LocalTime.of(7, 0)))
    }

    @Test fun `clock changes are rechecked within a minute without a zero delay loop`() {
        assertEquals(60_000L, QuietHoursBrightnessPolicy.nextRefreshDelayMillis(quiet, LocalTime.NOON))
        assertEquals(1L, QuietHoursBrightnessPolicy.nextRefreshDelayMillis(quiet, LocalTime.of(21, 59, 59, 999_999_999)))
    }
}
