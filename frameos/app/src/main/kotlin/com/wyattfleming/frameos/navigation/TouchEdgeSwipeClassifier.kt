package com.wyattfleming.frameos.navigation

import kotlin.math.abs

/**
 * Recognizes one deliberate, inward swipe which began at a screen edge.
 *
 * This is platform-independent so the routing policy can be tested without a
 * touchscreen or Android motion-event fixtures. A rejected gesture stays
 * rejected until its next down event, allowing the child view to retain it.
 */
internal class TouchEdgeSwipeClassifier(
    private val edgeStartPx: Float,
    private val minimumTravelPx: Float,
) {
    private var gesture: Gesture? = null

    init {
        require(edgeStartPx >= 0f) { "edgeStartPx must not be negative" }
        require(minimumTravelPx > 0f) { "minimumTravelPx must be positive" }
    }

    fun onDown(x: Float, y: Float, width: Float, pointerCount: Int = 1) {
        gesture = if (pointerCount == 1 && width > 0f) {
            when {
                x <= edgeStartPx -> Gesture(Edge.LEFT, x, y)
                x >= width - edgeStartPx -> Gesture(Edge.RIGHT, x, y)
                else -> null
            }
        } else {
            null
        }
    }

    fun onMove(x: Float, y: Float, pointerCount: Int = 1): ModeGestureDirection? {
        val current = gesture ?: return null
        if (pointerCount != 1) {
            gesture = null
            return null
        }
        val horizontal = x - current.startX
        val vertical = y - current.startY
        val horizontalDistance = abs(horizontal)
        val verticalDistance = abs(vertical)

        if (verticalDistance > horizontalDistance ||
            (current.edge == Edge.LEFT && horizontal < 0f) ||
            (current.edge == Edge.RIGHT && horizontal > 0f)
        ) {
            gesture = null
            return null
        }
        if (horizontalDistance < minimumTravelPx || horizontalDistance < verticalDistance * HORIZONTAL_DOMINANCE) {
            return null
        }

        gesture = null
        return if (current.edge == Edge.LEFT) {
            ModeGestureDirection.PREVIOUS
        } else {
            ModeGestureDirection.NEXT
        }
    }

    fun onCancel() {
        gesture = null
    }

    private data class Gesture(val edge: Edge, val startX: Float, val startY: Float)

    private enum class Edge { LEFT, RIGHT }

    private companion object {
        const val HORIZONTAL_DOMINANCE = 2f
    }
}
