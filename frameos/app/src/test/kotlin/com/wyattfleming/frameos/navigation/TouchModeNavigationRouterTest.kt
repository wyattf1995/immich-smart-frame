package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchModeNavigationRouterTest {
    private val router = TouchModeNavigationRouter(edgeStartPx = 24f, minimumTravelPx = 96f)

    @Test
    fun `only touchscreen edge swipes are intercepted`() {
        assertEquals(
            TouchModeNavigationRoute.PassToChild,
            router.route(down(x = 990f, touchscreen = false)),
        )
        assertEquals(
            TouchModeNavigationRoute.PassToChild,
            router.route(move(x = 894f, touchscreen = false)),
        )

        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(down(x = 990f)))
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.NEXT),
            router.route(move(x = 894f)),
        )
        assertEquals(TouchModeNavigationRoute.ConsumeIntercepted, router.route(move(x = 780f)))
        assertEquals(TouchModeNavigationRoute.ConsumeIntercepted, router.route(up()))
    }

    @Test
    fun `new down and ownership reset release a dropped intercepted stream`() {
        router.route(down(x = 10f))
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.PREVIOUS),
            router.route(move(x = 106f)),
        )

        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(down(x = 500f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 350f)))

        router.route(down(x = 990f))
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.NEXT),
            router.route(move(x = 894f)),
        )
        router.reset()
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 780f)))
    }

    @Test
    fun `center scrolls and multi touch remain with the child`() {
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(down(x = 500f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 350f)))

        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(down(x = 990f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 894f, pointerCount = 2)))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 780f)))
    }

    private fun down(x: Float, touchscreen: Boolean = true) = TouchInputSample(
        action = TouchInputAction.DOWN,
        x = x,
        y = 500f,
        width = 1_000f,
        pointerCount = 1,
        isTouchscreen = touchscreen,
    )

    private fun move(x: Float, pointerCount: Int = 1, touchscreen: Boolean = true) = TouchInputSample(
        action = TouchInputAction.MOVE,
        x = x,
        y = 500f,
        width = 1_000f,
        pointerCount = pointerCount,
        isTouchscreen = touchscreen,
    )

    private fun up() = TouchInputSample(
        action = TouchInputAction.UP,
        x = 780f,
        y = 500f,
        width = 1_000f,
        pointerCount = 1,
        isTouchscreen = true,
    )
}
