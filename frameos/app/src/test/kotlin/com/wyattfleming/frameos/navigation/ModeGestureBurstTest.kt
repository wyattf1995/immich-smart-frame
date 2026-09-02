package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeGestureBurstTest {
    private val modes = FrameMode.cycleModes(birdsConfigured = true)

    @Test
    fun `rapid gestures accumulate a logical destination without rendering intermediate modes`() {
        val burst = ModeGestureBurst()

        assertEquals(
            FrameMode.HOME,
            burst.offer(
                displayedMode = FrameMode.PHOTOS,
                direction = ModeGestureDirection.NEXT,
                eventTimeMillis = 1_000,
                receivedAtMillis = 1_000,
                repeatCount = 0,
                availableModes = modes,
            )?.targetMode,
        )
        assertEquals(
            FrameMode.WEATHER,
            burst.offer(
                displayedMode = FrameMode.PHOTOS,
                direction = ModeGestureDirection.NEXT,
                eventTimeMillis = 1_100,
                receivedAtMillis = 1_100,
                repeatCount = 0,
                availableModes = modes,
            )?.targetMode,
        )
        assertEquals(
            FrameMode.BIRDS,
            burst.offer(
                displayedMode = FrameMode.PHOTOS,
                direction = ModeGestureDirection.NEXT,
                eventTimeMillis = 1_200,
                receivedAtMillis = 1_200,
                repeatCount = 0,
                availableModes = modes,
            )?.targetMode,
        )

        assertEquals(FrameMode.BIRDS, burst.consumeTarget())
        assertNull(burst.consumeTarget())
    }

    @Test
    fun `opposite gestures adjust the pending destination instead of bouncing rendered views`() {
        val burst = ModeGestureBurst()

        offer(burst, ModeGestureDirection.NEXT, 2_000)
        offer(burst, ModeGestureDirection.NEXT, 2_100)
        val adjusted = offer(burst, ModeGestureDirection.PREVIOUS, 2_200)

        assertEquals(FrameMode.HOME, adjusted?.targetMode)
        assertEquals(FrameMode.HOME, burst.consumeTarget())
    }

    @Test
    fun `stale and repeated input is consumed without changing the pending destination`() {
        val burst = ModeGestureBurst(maxEventAgeMillis = 500)
        offer(burst, ModeGestureDirection.NEXT, 3_000)

        val repeated = burst.offer(
            displayedMode = FrameMode.PHOTOS,
            direction = ModeGestureDirection.NEXT,
            eventTimeMillis = 3_050,
            receivedAtMillis = 3_050,
            repeatCount = 1,
            availableModes = modes,
        )
        val stale = burst.offer(
            displayedMode = FrameMode.PHOTOS,
            direction = ModeGestureDirection.PREVIOUS,
            eventTimeMillis = 3_100,
            receivedAtMillis = 3_601,
            repeatCount = 0,
            availableModes = modes,
        )

        assertNull(repeated)
        assertNull(stale)
        assertEquals(FrameMode.HOME, burst.consumeTarget())
    }

    @Test
    fun `a long gesture burst has a bounded commit deadline`() {
        val burst = ModeGestureBurst(settleMillis = 240, maxBurstMillis = 900)

        val first = offer(burst, ModeGestureDirection.NEXT, 4_000)
        val nearLimit = offer(burst, ModeGestureDirection.NEXT, 4_850)
        val overdue = offer(burst, ModeGestureDirection.NEXT, 4_950)

        assertEquals(240, first?.commitDelayMillis)
        assertEquals(50, nearLimit?.commitDelayMillis)
        assertEquals(0, overdue?.commitDelayMillis)
    }

    @Test
    fun `cancel discards a pending burst before an explicit command`() {
        val burst = ModeGestureBurst()
        offer(burst, ModeGestureDirection.NEXT, 5_000)

        burst.cancel()

        assertNull(burst.consumeTarget())
    }

    private fun offer(
        burst: ModeGestureBurst,
        direction: ModeGestureDirection,
        now: Long,
    ): ModeGestureUpdate? = burst.offer(
        displayedMode = FrameMode.PHOTOS,
        direction = direction,
        eventTimeMillis = now,
        receivedAtMillis = now,
        repeatCount = 0,
        availableModes = modes,
    )
}
