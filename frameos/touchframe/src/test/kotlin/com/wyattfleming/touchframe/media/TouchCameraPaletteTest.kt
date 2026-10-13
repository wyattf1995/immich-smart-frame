package com.wyattfleming.touchframe.media

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCameraPaletteTest {
    @Test
    fun `camera controls keep readable foregrounds on each explicit palette`() {
        listOf(TouchCameraPalette.forNight(false), TouchCameraPalette.forNight(true)).forEach { palette ->
            assertTrue(TouchCameraPalette.contrastRatio(palette.ink, palette.surface) >= 4.5)
            assertTrue(TouchCameraPalette.contrastRatio(palette.accentInk, palette.accent) >= 4.5)
            assertTrue(TouchCameraPalette.contrastRatio(palette.disabledInk, palette.disabledSurface) >= 4.5)
        }
    }

    @Test
    fun `day and night camera surfaces are distinct`() {
        val day = TouchCameraPalette.forNight(false)
        val night = TouchCameraPalette.forNight(true)
        assertNotEquals(day.surface, night.surface)
        assertNotEquals(day.panel, night.panel)
    }
}
