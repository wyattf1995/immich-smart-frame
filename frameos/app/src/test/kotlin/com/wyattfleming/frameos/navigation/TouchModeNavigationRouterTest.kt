package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchModeNavigationRouterTest {
    private val router = TouchModeNavigationRouter(
        edgeStartPx = 24f,
        minimumTravelPx = 96f,
        directionalTouchSlopPx = 12f,
    )

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
    fun `cancel releases intercepted touch ownership`() {
        router.route(down(x = 10f))
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.PREVIOUS),
            router.route(move(x = 106f)),
        )
        assertEquals(TouchModeNavigationRoute.ConsumeIntercepted, router.route(cancel()))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(down(x = 500f)))
    }

    @Test
    fun `center scrolls and multi touch remain with the child`() {
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(down(x = 500f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 350f)))

        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(down(x = 990f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 894f, pointerCount = 2)))
        assertEquals(TouchModeNavigationRoute.PassToChild, router.route(move(x = 780f)))
    }

    @Test
    fun `policy denied edge gesture stays with child and later reset permits a fresh route`() {
        var allowPrevious = false
        val policyRouter = TouchModeNavigationRouter(
            edgeStartPx = 24f,
            minimumTravelPx = 96f,
            directionalTouchSlopPx = 12f,
            shouldRoute = { direction -> allowPrevious && direction == ModeGestureDirection.PREVIOUS },
        )

        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 10f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(move(x = 106f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(move(x = 200f)))

        policyRouter.reset()
        allowPrevious = true
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 10f)))
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.PREVIOUS),
            policyRouter.route(move(x = 106f)),
        )
    }

    @Test
    fun `only first edge route is intercepted after menu policy closes`() {
        var menuOpen = false
        var interceptions = 0
        val policyRouter = TouchModeNavigationRouter(
            edgeStartPx = 24f,
            minimumTravelPx = 96f,
            directionalTouchSlopPx = 12f,
            shouldRoute = { direction ->
                if (!menuOpen && direction == ModeGestureDirection.PREVIOUS) {
                    interceptions += 1
                    menuOpen = true
                    true
                } else {
                    false
                }
            },
        )

        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 10f)))
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.PREVIOUS),
            policyRouter.route(move(x = 106f)),
        )
        assertEquals(TouchModeNavigationRoute.ConsumeIntercepted, policyRouter.route(up()))

        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 10f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(move(x = 106f)))
        assertEquals(1, interceptions)
    }

    @Test
    fun `immersive edge width is admitted only at down and remains frozen for the gesture`() {
        var immersiveMedia = true
        val policyRouter = TouchModeNavigationRouter(
            edgeStartPx = 24f,
            minimumTravelPx = 96f,
            directionalTouchSlopPx = 12f,
            edgeStartPxForDown = { if (immersiveMedia) 48f else 24f },
            shouldRoute = { it == ModeGestureDirection.PREVIOUS },
        )

        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 36f)))
        immersiveMedia = false
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.PREVIOUS),
            policyRouter.route(move(x = 132f)),
        )
        assertEquals(TouchModeNavigationRoute.ConsumeIntercepted, policyRouter.route(up()))

        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 36f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(move(x = 132f)))

        immersiveMedia = true
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 48f)))
        assertEquals(
            TouchModeNavigationRoute.Intercept(ModeGestureDirection.PREVIOUS),
            policyRouter.route(move(x = 144f)),
        )
        assertEquals(TouchModeNavigationRoute.ConsumeIntercepted, policyRouter.route(up()))

        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 49f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(move(x = 145f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(down(x = 500f)))
        assertEquals(TouchModeNavigationRoute.PassToChild, policyRouter.route(move(x = 596f)))
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

    private fun cancel() = TouchInputSample(
        action = TouchInputAction.CANCEL,
        x = 106f,
        y = 500f,
        width = 1_000f,
        pointerCount = 1,
        isTouchscreen = true,
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
