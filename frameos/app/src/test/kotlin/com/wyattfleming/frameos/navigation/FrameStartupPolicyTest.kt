package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStartupPolicyTest {
    @Test
    fun `reboot starts at Home with no stale local overrides`() {
        val state = FrameStartupPolicy.initialState()

        assertEquals(FrameMode.HOME, state.mode)
        assertFalse(state.photosPaused)
        assertNull(state.brightnessOverridePercent)
    }
}
