package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchEdgeSwipeClassifierTest {
    private val screenWidth = 1_000f

    @Test
    fun `right edge inward swipe moves to the next mode once`() {
        val classifier = classifier()

        classifier.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)

        assertEquals(ModeGestureDirection.NEXT, classifier.onMove(x = 894f, y = 500f))
        assertNull(classifier.onMove(x = 780f, y = 500f))
    }

    @Test
    fun `left edge inward swipe moves to the previous mode`() {
        val classifier = classifier()

        classifier.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)

        assertEquals(ModeGestureDirection.PREVIOUS, classifier.onMove(x = 106f, y = 500f))
    }

    @Test
    fun `center swipes and outward edge swipes remain unhandled`() {
        val center = classifier()
        center.onDown(x = 500f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(center.onMove(x = 350f, y = 500f))

        val outward = classifier()
        outward.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(outward.onMove(x = 106f - 192f, y = 500f))
    }

    @Test
    fun `travel and horizontal dominance thresholds are enforced`() {
        val short = classifier()
        short.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(short.onMove(x = 895f, y = 500f))

        val diagonal = classifier()
        diagonal.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(diagonal.onMove(x = 894f, y = 451f))

        val valid = classifier()
        valid.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertEquals(ModeGestureDirection.NEXT, valid.onMove(x = 894f, y = 452f))
    }

    @Test
    fun `vertical intent multi touch cancel and action cancel suppress navigation`() {
        val vertical = classifier()
        vertical.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(vertical.onMove(x = 40f, y = 600f))
        assertNull(vertical.onMove(x = 160f, y = 600f))

        val multiTouch = classifier()
        multiTouch.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(multiTouch.onMove(x = 894f, y = 500f, pointerCount = 2))
        assertNull(multiTouch.onMove(x = 780f, y = 500f))

        val cancelled = classifier()
        cancelled.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        cancelled.onCancel()
        assertNull(cancelled.onMove(x = 894f, y = 500f))
    }

    @Test
    fun `sub slop outward and vertical jitter recovers into a deliberate inward swipe`() {
        val left = classifier()
        left.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(left.onMove(x = 8f, y = 503f))
        assertEquals(ModeGestureDirection.PREVIOUS, left.onMove(x = 106f, y = 500f))

        val right = classifier()
        right.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(right.onMove(x = 992f, y = 497f))
        assertEquals(ModeGestureDirection.NEXT, right.onMove(x = 894f, y = 500f))
    }

    @Test
    fun `directional slop boundary drift recovers on both edges`() {
        val leftOutward = classifier()
        leftOutward.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(leftOutward.onMove(x = -2f, y = 500f))
        assertEquals(ModeGestureDirection.PREVIOUS, leftOutward.onMove(x = 106f, y = 500f))

        val leftVertical = classifier()
        leftVertical.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(leftVertical.onMove(x = 10f, y = 512f))
        assertEquals(ModeGestureDirection.PREVIOUS, leftVertical.onMove(x = 106f, y = 500f))

        val rightOutward = classifier()
        rightOutward.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(rightOutward.onMove(x = 1_002f, y = 500f))
        assertEquals(ModeGestureDirection.NEXT, rightOutward.onMove(x = 894f, y = 500f))

        val rightVertical = classifier()
        rightVertical.onDown(x = 990f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(rightVertical.onMove(x = 990f, y = 512f))
        assertEquals(ModeGestureDirection.NEXT, rightVertical.onMove(x = 894f, y = 500f))
    }

    @Test
    fun `deliberate outward and vertical motion reject after directional slop`() {
        val outward = classifier()
        outward.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(outward.onMove(x = -3f, y = 500f))
        assertNull(outward.onMove(x = 106f, y = 500f))

        val vertical = classifier()
        vertical.onDown(x = 10f, y = 500f, width = screenWidth, edgeStartPx = 24f)
        assertNull(vertical.onMove(x = 14f, y = 513f))
        assertNull(vertical.onMove(x = 106f, y = 500f))
    }

    private fun classifier() = TouchEdgeSwipeClassifier(
        minimumTravelPx = 96f,
        directionalTouchSlopPx = 12f,
    )
}
