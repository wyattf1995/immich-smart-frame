package com.wyattfleming.frameos.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchDashboardDaylightTest {
    @Test
    fun daylightBoundariesAreInclusiveAtSevenAndExclusiveAtNineteen() {
        assertFalse(TouchDashboardDaylight.isDaylight(6))
        assertTrue(TouchDashboardDaylight.isDaylight(7))
        assertTrue(TouchDashboardDaylight.isDaylight(18))
        assertFalse(TouchDashboardDaylight.isDaylight(19))
    }
}
