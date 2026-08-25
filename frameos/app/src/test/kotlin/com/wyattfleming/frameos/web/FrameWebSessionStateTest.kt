package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebSessionStateTest {
    @Test
    fun `successful request is reused until a load failure marks it dirty`() {
        val state = FrameWebSessionState()
        val url = "https://home-assistant.example.invalid/local/frameos-panel.html#home"

        assertTrue(state.shouldRequest(url))
        state.recordRequest(url)
        assertFalse(state.shouldRequest(url))

        state.recordFailure()
        assertTrue(state.shouldRequest(url))
    }

    @Test
    fun `a different route always requests navigation`() {
        val state = FrameWebSessionState()
        state.recordRequest("https://home-assistant.example.invalid/local/frameos-panel.html#home")

        assertTrue(
            state.shouldRequest("https://home-assistant.example.invalid/local/frameos-panel.html#calendar"),
        )
    }

    @Test
    fun `retains the approved request for crash recovery and records a successful surface`() {
        val state = FrameWebSessionState()
        val url = "https://home-assistant.example.invalid/local/frameos-panel.html#home"

        state.recordRequest(url)
        state.recordSuccess()
        state.recordFailure()

        assertEquals(url, state.recoveryUrl())
        assertTrue(state.shouldRequest(url))
    }
}
