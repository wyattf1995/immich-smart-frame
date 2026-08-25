package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebHiddenSessionLifecycleTest {
    @Test
    fun `calendar preload starts the hidden lifetime and unrelated views do not reset it`() {
        val lifecycle = FrameWebHiddenSessionLifecycle()

        lifecycle.onHomePreloaded(nowMillis = 100L)
        lifecycle.onNonHomeShown()
        lifecycle.onNonHomeShown()

        assertEquals(100L, lifecycle.hiddenSinceMillis)
    }

    @Test
    fun `showing Home resets hidden age and eviction invalidates the delayed preload callback`() {
        val lifecycle = FrameWebHiddenSessionLifecycle()
        val preloadGeneration = lifecycle.onHomePreloaded(nowMillis = 100L)

        lifecycle.onHomeShown()
        assertNull(lifecycle.hiddenSinceMillis)
        lifecycle.onHomePreloaded(nowMillis = 200L)
        lifecycle.onEvicted()

        assertFalse(lifecycle.isCurrentPreload(preloadGeneration))
        assertTrue(lifecycle.hiddenSinceMillis == null)
    }
}
