package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrameWebCrashRecoveryStateTest {
    @Test
    fun `crash or kill requires exactly one new session generation before reload`() {
        val state = FrameWebCrashRecoveryState()

        assertEquals(0, state.generation)
        state.markClosedByContentProcess()
        assertTrue(state.requiresReopen)
        assertTrue(state.reopenIfRequired { })
        assertEquals(1, state.generation)
        assertFalse(state.requiresReopen)
        assertFalse(state.reopenIfRequired { })
    }

    @Test
    fun `closed content session rejects lifecycle operations until reopened`() {
        val state = FrameWebCrashRecoveryState()

        state.markClosedByContentProcess()

        assertFalse(state.canUseSession)
        assertTrue(state.reopenIfRequired { })
        assertTrue(state.canUseSession)
    }

    @Test
    fun `failed reopen keeps the content session closed for the next retry`() {
        val state = FrameWebCrashRecoveryState()
        state.markClosedByContentProcess()

        try {
            state.reopenIfRequired { throw IllegalStateException("open failed") }
            fail("Expected reopen failure")
        } catch (_: IllegalStateException) {
            // Expected: the caller controls the actual Gecko open operation.
        }

        assertTrue(state.requiresReopen)
        assertFalse(state.canUseSession)
        assertEquals(0, state.generation)
        assertTrue(state.reopenIfRequired { })
        assertEquals(1, state.generation)
        assertTrue(state.canUseSession)
    }
}
