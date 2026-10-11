package com.wyattfleming.frameos.ui

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import com.wyattfleming.frameos.navigation.ModeGestureDirection
import com.wyattfleming.frameos.navigation.TouchInputAction
import com.wyattfleming.frameos.navigation.TouchInputSample
import com.wyattfleming.frameos.navigation.TouchModeNavigationRoute
import com.wyattfleming.frameos.navigation.TouchModeNavigationRouter

/**
 * Gives a physical touchscreen an edge-only mode switch without taking normal
 * web content taps, scrolling, or center-originated gestures from its child.
 */
internal class FrameTouchInputLayout private constructor(
    context: Context,
    attrs: AttributeSet?,
    private val onModeGesture: (ModeGestureDirection, Long) -> Unit,
) : FrameLayout(context, attrs) {
    constructor(context: Context) : this(context, null, { _, _ -> })

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, { _, _ -> })

    constructor(
        context: Context,
        onModeGesture: (ModeGestureDirection, Long) -> Unit,
    ) : this(context, null, onModeGesture)

    private val router = TouchModeNavigationRouter(
        edgeStartPx = context.dp(EDGE_START_DP).toFloat(),
        minimumTravelPx = context.dp(MINIMUM_TRAVEL_DP).toFloat(),
    )
    private var consumeCurrentEvent = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val route = router.route(event.asTouchInputSample())
        consumeCurrentEvent = route is TouchModeNavigationRoute.Intercept ||
            route == TouchModeNavigationRoute.ConsumeIntercepted
        if (route is TouchModeNavigationRoute.Intercept) {
            // A child may have disallowed interception while starting a web
            // scroll. Clear that flag before super dispatches this same event;
            // onInterceptTouchEvent then sends the child ACTION_CANCEL.
            super.requestDisallowInterceptTouchEvent(false)
            onModeGesture(route.direction, event.eventTime)
        }
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            consumeCurrentEvent = false
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        router.isIntercepting() || super.onInterceptTouchEvent(event)

    /** Prevent the legacy fling detector from seeing an intercepted swipe. */
    fun consumesCurrentModeGesture(): Boolean = consumeCurrentEvent

    /** Releases a gesture whose final UP/CANCEL was lost with window ownership. */
    fun resetTouchNavigation() {
        consumeCurrentEvent = false
        router.reset()
    }

    private fun MotionEvent.asTouchInputSample(): TouchInputSample = TouchInputSample(
        action = when (actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchInputAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchInputAction.MOVE
            MotionEvent.ACTION_UP -> TouchInputAction.UP
            MotionEvent.ACTION_CANCEL -> TouchInputAction.CANCEL
            MotionEvent.ACTION_POINTER_DOWN -> TouchInputAction.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchInputAction.POINTER_UP
            else -> TouchInputAction.CANCEL
        },
        x = x,
        y = y,
        width = width.toFloat(),
        pointerCount = pointerCount,
        isTouchscreen = isFromSource(InputDevice.SOURCE_TOUCHSCREEN),
    )

    private companion object {
        const val EDGE_START_DP = 24
        const val MINIMUM_TRAVEL_DP = 96
    }
}
