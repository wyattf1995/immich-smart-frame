package com.wyattfleming.frameos.ui

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import com.wyattfleming.frameos.navigation.ModeGestureDirection
import com.wyattfleming.frameos.navigation.TouchEdgeSwipeClassifier

/**
 * Gives a physical touchscreen an edge-only mode switch without taking normal
 * web content taps, scrolling, or center-originated gestures from its child.
 */
internal class FrameTouchInputLayout(
    context: Context,
    private val onModeGesture: (ModeGestureDirection, Long) -> Unit,
) : FrameLayout(context) {
    private val classifier = TouchEdgeSwipeClassifier(
        edgeStartPx = context.dp(EDGE_START_DP).toFloat(),
        minimumTravelPx = context.dp(MINIMUM_TRAVEL_DP).toFloat(),
    )
    private var interceptingModeGesture = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            classifier.onCancel()
            return super.onInterceptTouchEvent(event)
        }
        if (interceptingModeGesture) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> classifier.onDown(
                x = event.x,
                y = event.y,
                width = width.toFloat(),
                pointerCount = event.pointerCount,
            )
            MotionEvent.ACTION_MOVE -> classifier.onMove(
                x = event.x,
                y = event.y,
                pointerCount = event.pointerCount,
            )?.let { direction ->
                interceptingModeGesture = true
                onModeGesture(direction, event.eventTime)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> classifier.onCancel()
        }
        return super.onInterceptTouchEvent(event)
    }

    /**
     * Called from the root's touch listener after interception so the legacy
     * fling detector cannot emit a second mode transition for the same swipe.
     */
    fun consumeInterceptedModeGesture(event: MotionEvent): Boolean {
        if (!interceptingModeGesture) return false
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            interceptingModeGesture = false
            classifier.onCancel()
        }
        return true
    }

    private companion object {
        const val EDGE_START_DP = 24
        const val MINIMUM_TRAVEL_DP = 96
    }
}
