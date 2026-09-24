package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebWatchdogRecoveryStateTest {
    @Test
    fun `session recreation begins on the third consecutive watchdog timeout`() {
        val state = state()

        assertFalse(recordWatchdogTimeout(state))
        assertFalse(recordWatchdogTimeout(state))
        assertTrue(recordWatchdogTimeout(state))
        assertTrue(requiresSessionRecreation(state))
        assertEquals(3, consecutiveWatchdogTimeouts(state))
    }

    @Test
    fun `only a paint qualified render clears watchdog timeout history`() {
        val state = state()

        repeat(3) { recordWatchdogTimeout(state) }
        state.javaClass.getMethod("recordNavigationStart").invoke(state)
        assertTrue(requiresSessionRecreation(state))
        assertEquals(3, consecutiveWatchdogTimeouts(state))

        state.javaClass.getMethod("recordRendered").invoke(state)

        assertFalse(requiresSessionRecreation(state))
        assertEquals(0, consecutiveWatchdogTimeouts(state))
    }

    private fun state(): Any = Class.forName("com.wyattfleming.frameos.web.FrameWebWatchdogRecoveryState")
        .getConstructor()
        .newInstance()

    private fun recordWatchdogTimeout(state: Any): Boolean =
        state.javaClass.getMethod("recordWatchdogTimeout").invoke(state) as Boolean

    private fun requiresSessionRecreation(state: Any): Boolean =
        state.javaClass.getMethod("getRequiresSessionRecreation").invoke(state) as Boolean

    private fun consecutiveWatchdogTimeouts(state: Any): Int =
        state.javaClass.getMethod("getConsecutiveWatchdogTimeouts").invoke(state) as Int
}
