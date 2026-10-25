package com.wyattfleming.frameos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeSceneDensityTest {
    @Test
    fun `the Lenovo's 1080-pixel, 540 dp screen renders the shared screens at 216 dpi, the panel's designed 800 dp height`() {
        assertEquals(216, NativeSceneDensity.dpiFor(heightPx = 1080, heightDp = 540))
        assertEquals(800, 1080 * 160 / 216)
        assertEquals("never rounds below the designed height", 213, NativeSceneDensity.dpiFor(heightPx = 1066, heightDp = 533))
        assertEquals(216, NativeSceneDensity.dpiFor(heightPx = 1080, heightDp = 720))
    }

    @Test
    fun `screens already tall enough keep their own density`() {
        assertNull(NativeSceneDensity.dpiFor(heightPx = 800, heightDp = 800))
        assertNull(NativeSceneDensity.dpiFor(heightPx = 1080, heightDp = 1080))
        assertNull(NativeSceneDensity.dpiFor(heightPx = 0, heightDp = 0))
    }
}
