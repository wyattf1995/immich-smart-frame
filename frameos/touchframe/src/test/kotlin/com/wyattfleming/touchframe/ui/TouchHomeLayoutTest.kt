package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchHomeLayoutTest {
    @Test
    fun `first measured compact size rebuilds a large fallback layout exactly once`() {
        val fallbackLarge = TouchHomeLayout.isLargeLandscape(1920f, 1080f)
        val measuredCompact = TouchHomeLayout.isLargeLandscape(800f, 480f)

        assertTrue(fallbackLarge)
        assertFalse(measuredCompact)
        assertTrue(TouchHomeLayout.needsRebuild(fallbackLarge, measuredCompact))
        assertTrue(TouchHomeLayout.needsRebuild(measuredCompact, fallbackLarge))
        assertFalse(TouchHomeLayout.needsRebuild(measuredCompact, measuredCompact))
    }

    @Test
    fun `configuration reports compact and wide landscape layout classes`() {
        assertFalse(TouchHomeLayout.isLargeLandscape(800f, 480f))
        assertTrue(TouchHomeLayout.isLargeLandscape(1920f, 1080f))
        assertFalse(TouchHomeLayout.isLargeLandscape(800f, 1000f))
    }
}
