package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebRenderedStateTest {
    @Test
    fun `successful page stop followed by contentful paint becomes rendered once`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        assertFalse(state.recordPageStop(success = true))
        assertTrue(state.recordFirstContentfulPaint())
        assertFalse(state.recordFirstContentfulPaint())
        assertTrue(state.rendered)
    }

    @Test
    fun `contentful paint followed by successful page stop becomes rendered once`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        assertFalse(state.recordFirstContentfulPaint())
        assertTrue(state.recordPageStop(success = true))
        assertFalse(state.recordPageStop(success = true))
        assertTrue(state.rendered)
    }

    @Test
    fun `failed page stop never qualifies an error page as rendered`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        assertFalse(state.recordFirstContentfulPaint())
        assertFalse(state.recordPageStop(success = false))
        assertFalse(state.rendered)
    }

    @Test
    fun `new requests and failures discard stale rendered state`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStop(success = true)
        state.recordFirstContentfulPaint()
        assertTrue(state.rendered)

        state.recordRequest()
        assertFalse(state.rendered)
        assertFalse(state.recordPageStop(success = true))

        state.recordFirstContentfulPaint()
        assertTrue(state.rendered)
        state.recordFailure()
        assertFalse(state.rendered)
    }
}
