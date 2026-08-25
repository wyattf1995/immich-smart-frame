package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
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
}
