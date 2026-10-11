package com.wyattfleming.frameos.navigation

internal enum class TouchInputAction { DOWN, MOVE, UP, CANCEL, POINTER_DOWN, POINTER_UP }

internal data class TouchInputSample(
    val action: TouchInputAction,
    val x: Float,
    val y: Float,
    val width: Float,
    val pointerCount: Int,
    val isTouchscreen: Boolean,
)

internal sealed interface TouchModeNavigationRoute {
    data object PassToChild : TouchModeNavigationRoute
    data class Intercept(val direction: ModeGestureDirection) : TouchModeNavigationRoute
    data object ConsumeIntercepted : TouchModeNavigationRoute
}

/**
 * Routes only a qualified physical-touchscreen edge swipe to mode navigation.
 * It deliberately observes events before ViewGroup interception so a web child
 * calling requestDisallowInterceptTouchEvent(true) cannot hide that swipe.
 */
internal class TouchModeNavigationRouter(
    edgeStartPx: Float,
    minimumTravelPx: Float,
) {
    private val classifier = TouchEdgeSwipeClassifier(edgeStartPx, minimumTravelPx)
    private var intercepting = false

    fun route(sample: TouchInputSample): TouchModeNavigationRoute {
        if (sample.action == TouchInputAction.DOWN) reset()
        if (!sample.isTouchscreen) {
            reset()
            return TouchModeNavigationRoute.PassToChild
        }
        if (intercepting) {
            if (sample.action == TouchInputAction.UP || sample.action == TouchInputAction.CANCEL) reset()
            return TouchModeNavigationRoute.ConsumeIntercepted
        }
        when (sample.action) {
            TouchInputAction.DOWN -> classifier.onDown(sample.x, sample.y, sample.width, sample.pointerCount)
            TouchInputAction.MOVE -> classifier.onMove(sample.x, sample.y, sample.pointerCount)?.let { direction ->
                intercepting = true
                return TouchModeNavigationRoute.Intercept(direction)
            }
            TouchInputAction.UP,
            TouchInputAction.CANCEL,
            TouchInputAction.POINTER_DOWN,
            TouchInputAction.POINTER_UP -> classifier.onCancel()
        }
        return TouchModeNavigationRoute.PassToChild
    }

    fun isIntercepting(): Boolean = intercepting

    fun reset() {
        intercepting = false
        classifier.onCancel()
    }
}
