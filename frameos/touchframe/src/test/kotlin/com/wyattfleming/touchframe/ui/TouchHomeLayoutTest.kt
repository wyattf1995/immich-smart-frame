package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchHomeLayoutTest {
    @Test
    fun `layout transition emits initial and changed classes exactly once`() {
        val transition = TouchHomeLayoutTransition()

        assertTrue(transition.request(true))
        assertEquals(true, transition.consume())
        assertNull(transition.consume())
        assertFalse(transition.request(true))
        assertTrue(transition.request(false))
        assertEquals(false, transition.consume())
        assertNull(transition.consume())
    }

    @Test
    fun `configuration and measured Home content use the same compact boundary`() {
        val configurationContentWidth = TouchHomeLayout.contentWidthDp(920f, 80f)
        val measuredContentWidth = 840f

        assertEquals(measuredContentWidth, configurationContentWidth)
        assertFalse(TouchHomeLayout.isLargeLandscape(configurationContentWidth, 480f))
        assertFalse(TouchHomeLayout.isLargeLandscape(measuredContentWidth, 480f))
        assertTrue(TouchHomeLayout.isLargeLandscape(TouchHomeLayout.contentWidthDp(1920f, 96f), 1080f))
    }
}
