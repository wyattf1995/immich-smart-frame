package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebPageLoadWatchdogStateTest {
    @Test
    fun `stale timer generations cannot fire after rearm or disarm`() {
        val state = FrameWebPageLoadWatchdogState()

        val first = state.arm(startedAtMillis = 100L)
        val second = state.arm(startedAtMillis = 250L)

        assertNull(state.markTimedOut(first.generation))
        assertEquals(
            FrameWebPageLoadDisarm(
                hadActiveLoad = true,
                timedOut = false,
                startedAtMillis = 250L,
            ),
            state.disarm(),
        )
        assertNull(state.markTimedOut(second.generation))
    }

    @Test
    fun `timed out load disarms once and resets to an inert lifecycle`() {
        val state = FrameWebPageLoadWatchdogState()

        val arm = state.arm(startedAtMillis = 900L)

        assertEquals(900L, state.markTimedOut(arm.generation))
        assertEquals(
            FrameWebPageLoadDisarm(
                hadActiveLoad = false,
                timedOut = true,
                startedAtMillis = 900L,
            ),
            state.disarm(),
        )

        val inert = state.disarm()
        assertFalse(inert.hadActiveLoad)
        assertFalse(inert.timedOut)
        assertEquals(0L, inert.startedAtMillis)
    }

    @Test
    fun `generation increments across each arm and disarm boundary`() {
        val state = FrameWebPageLoadWatchdogState()

        val first = state.arm(startedAtMillis = 10L)
        assertEquals(1L, first.generation)
        assertTrue(state.disarm().hadActiveLoad)

        val second = state.arm(startedAtMillis = 20L)
        assertEquals(3L, second.generation)
        assertEquals(20L, state.markTimedOut(second.generation))
    }
}
