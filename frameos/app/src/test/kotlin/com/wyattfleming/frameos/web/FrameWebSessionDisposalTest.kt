package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebSessionDisposalTest {
    @Test
    fun `preparation stops an attached session in resource release order`() {
        val calls = mutableListOf<String>()

        val clean = FrameWebSessionDisposal.prepare(
            clearFocus = { calls += "focus" },
            deactivate = { calls += "active" },
            stop = { calls += "stop" },
        )

        assertTrue(clean)
        assertEquals(listOf("focus", "active", "stop"), calls)
    }

    @Test
    fun `preparation attempts stop when an earlier Gecko operation fails`() {
        val calls = mutableListOf<String>()

        val clean = FrameWebSessionDisposal.prepare(
            clearFocus = {
                calls += "focus"
                error("detached focus")
            },
            deactivate = {
                calls += "active"
                error("detached active state")
            },
            stop = { calls += "stop" },
        )

        assertFalse(clean)
        assertEquals(listOf("focus", "active", "stop"), calls)
    }
}
