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

    @Test
    fun `consuming a pending recreation does not reset timeout history or recreate again before the next threshold`() {
        val state = state()

        repeat(3) { recordWatchdogTimeout(state) }
        state.javaClass.getMethod("markSessionRecreated").invoke(state)

        assertFalse(requiresSessionRecreation(state))
        assertEquals(3, consecutiveWatchdogTimeouts(state))
        assertFalse(recordWatchdogTimeout(state))
        assertFalse(recordWatchdogTimeout(state))
        assertTrue(recordWatchdogTimeout(state))
        assertTrue(requiresSessionRecreation(state))
        assertEquals(6, consecutiveWatchdogTimeouts(state))
    }

    @Test
    fun `bridge ready continuation after replacement loads without a second replacement before paint`() {
        val state = state()

        repeat(3) { recordWatchdogTimeout(state) }
        assertTrue(requiresSessionRecreation(state))

        // The visible retry opened a replacement session. Its Photos bridge may become ready
        // before Gecko reports page stop plus paint and continue the deferred load immediately.
        state.javaClass.getMethod("markSessionRecreated").invoke(state)
        state.javaClass.getMethod("recordNavigationStart").invoke(state)

        assertFalse(requiresSessionRecreation(state))
        assertEquals(3, consecutiveWatchdogTimeouts(state))
    }

    @Test
    fun `activation failure after opening a replacement leaves the next retry pending for replacement`() {
        val state = state()

        repeat(3) { recordWatchdogTimeout(state) }
        assertTrue(requiresSessionRecreation(state))

        // Opening succeeded, but activation failed before the replacement could be committed.
        state.javaClass.getMethod("recordNavigationStart").invoke(state)

        assertTrue(requiresSessionRecreation(state))
        assertEquals(3, consecutiveWatchdogTimeouts(state))
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
