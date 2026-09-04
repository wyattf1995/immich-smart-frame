package com.wyattfleming.frameos.config

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class FrameExperienceSettingsTest {
    @Test fun `parents preset is deliberately photo weather only`() {
        assertEquals(listOf(FrameMode.PHOTOS, FrameMode.WEATHER), FrameExperienceSettings.PARENTS_PHOTOS_WEATHER.orderedEnabledModes)
    }

    @Test fun `quiet hours cross midnight and never override a manual value`() {
        val quiet = QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0), 10)
        assertTrue(quiet.isActiveAt(LocalTime.of(23, 0)))
        assertTrue(quiet.isActiveAt(LocalTime.of(6, 59)))
        assertFalse(quiet.isActiveAt(LocalTime.of(7, 0)))
        assertEquals(10, QuietHoursBrightnessPolicy.effectivePercent(null, quiet, LocalTime.of(23, 0)))
        assertEquals(70, QuietHoursBrightnessPolicy.effectivePercent(70, quiet, LocalTime.of(23, 0)))
        assertNull(QuietHoursBrightnessPolicy.effectivePercent(null, quiet, LocalTime.of(12, 0)))
    }
}
