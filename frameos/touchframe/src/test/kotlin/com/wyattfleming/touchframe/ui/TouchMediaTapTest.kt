package com.wyattfleming.touchframe.ui

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Disclosure audit 2026-10-15: on Photos and Art the menu hides behind a left-edge swipe, and the hint used to appear
 * only after a tab tap into them, three times per frame. Photos usually arrives by itself (idle return, the Home
 * button, a restart), so a tap on the photo, the natural "where are the controls" gesture, now shows the hint.
 */
class TouchMediaTapTest {
    private fun tap(detector: TouchMediaTap, destination: TouchDestination = TouchDestination.PHOTOS, menuVisible: Boolean = false,
                    upX: Float = 602f, upY: Float = 401f, upAt: Long = 1_150L): Boolean {
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, 600f, 400f, 1_000L, destination, menuVisible, SLOP))
        return detector.onEvent(MotionEvent.ACTION_UP, upX, upY, upAt, destination, menuVisible, SLOP)
    }

    @Test
    fun `a short still tap on the photo while the menu is hidden is reported`() {
        assertTrue(tap(TouchMediaTap()))
        assertTrue(tap(TouchMediaTap(), destination = TouchDestination.ART))
    }

    @Test
    fun `swipes, long presses, native screens and an open menu are not taps on the photo`() {
        assertFalse("a swipe", tap(TouchMediaTap(), upX = 760f))
        assertFalse("a long press", tap(TouchMediaTap(), upAt = 1_800L))
        assertFalse("Home is not immersive", tap(TouchMediaTap(), destination = TouchDestination.HOME))
        assertFalse("the menu is already open", tap(TouchMediaTap(), menuVisible = true))
        val cancelled = TouchMediaTap()
        cancelled.onEvent(MotionEvent.ACTION_DOWN, 600f, 400f, 1_000L, TouchDestination.PHOTOS, false, SLOP)
        assertFalse(cancelled.onEvent(MotionEvent.ACTION_CANCEL, 600f, 400f, 1_100L, TouchDestination.PHOTOS, false, SLOP))
    }

    @Test
    fun `the edge hint shows on a tap at most once every ten minutes`() {
        assertTrue(TouchEdgeHintPolicy.showOnTap(nowMillis = 5_000L, lastShownMillis = null))
        assertFalse(TouchEdgeHintPolicy.showOnTap(nowMillis = 5_000L + 9 * 60_000L, lastShownMillis = 5_000L))
        assertTrue(TouchEdgeHintPolicy.showOnTap(nowMillis = 5_000L + 10 * 60_000L, lastShownMillis = 5_000L))
    }

    private companion object {
        const val SLOP = 16
    }
}
