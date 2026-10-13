package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchShellLayoutTest {
    @Test
    fun `only media destinations accept a qualified left edge reveal`() {
        assertTrue(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 104f, 430f, 48))
        assertTrue(TouchShellGesture.shouldReveal(TouchDestination.ART, 48f, 420f, 104f, 430f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.HOME, 24f, 420f, 104f, 430f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.WEATHER, 24f, 420f, 104f, 430f, 48))
    }

    @Test
    fun `edge reveal requires an inward horizontal gesture with limited vertical drift`() {
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 49f, 420f, 120f, 420f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 79f, 420f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 104f, 453f, 48))
        assertFalse(TouchShellGesture.shouldReveal(TouchDestination.PHOTOS, 24f, 420f, 1f, 420f, 48))
    }

    @Test
    fun `wide layouts use the intended rail width`() {
        assertEquals(80, TouchShellLayout.railWidthDpFor(1399))
        assertEquals(96, TouchShellLayout.railWidthDpFor(1400))
    }

    @Test
    fun `selected and Settings rail ink satisfy AA contrast in both palettes`() {
        listOf(false, true).forEach { night ->
            val palette = Palette.forNight(night)
            assertTrue(
                "selected rail ink must be at least 4.5:1 in ${if (night) "night" else "day"} mode",
                Palette.contrastRatio(palette.selectedInk, palette.selected) >= 4.5,
            )
            assertTrue(
                "Settings rail ink must be at least 4.5:1 in ${if (night) "night" else "day"} mode",
                Palette.contrastRatio(palette.settingsInk, palette.settings) >= 4.5,
            )
        }
    }

    @Test
    fun `only photos and art are fullscreen destinations`() {
        assertTrue(TouchDestination.PHOTOS.isImmersiveMedia)
        assertTrue(TouchDestination.ART.isImmersiveMedia)
        assertFalse(TouchDestination.HOME.isImmersiveMedia)
        assertFalse(TouchDestination.CAMERAS.isImmersiveMedia)
        assertFalse(TouchDestination.CALENDAR.isImmersiveMedia)
        assertFalse(TouchDestination.WEATHER.isImmersiveMedia)
    }
}
