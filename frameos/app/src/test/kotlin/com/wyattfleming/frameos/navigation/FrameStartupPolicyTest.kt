package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FrameStartupPolicyTest {
    @Test
    fun `reboot starts at Photos with no stale local overrides`() {
        val state = FrameStartupPolicy.initialState()

        assertEquals(FrameMode.PHOTOS, state.mode)
        assertFalse(state.photosPaused)
        assertNull(state.brightnessOverridePercent)
    }
}
