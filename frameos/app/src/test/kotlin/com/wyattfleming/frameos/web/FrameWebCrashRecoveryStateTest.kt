package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebCrashRecoveryStateTest {
    @Test
    fun `crash or kill requires exactly one new session generation before reload`() {
        val state = FrameWebCrashRecoveryState()

        assertEquals(0, state.generation)
        state.markClosedByContentProcess()
        assertTrue(state.requiresReopen)
        assertTrue(state.reopenIfRequired())
        assertEquals(1, state.generation)
        assertFalse(state.requiresReopen)
        assertFalse(state.reopenIfRequired())
    }

    @Test
    fun `closed content session rejects lifecycle operations until reopened`() {
        val state = FrameWebCrashRecoveryState()

        state.markClosedByContentProcess()

        assertFalse(state.canUseSession)
        assertTrue(state.reopenIfRequired())
        assertTrue(state.canUseSession)
    }
}
