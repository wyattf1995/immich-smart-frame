package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebRenderedStateTest {
    @Test
    fun `successful page stop followed by contentful paint becomes rendered once`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        assertFalse(state.recordPageStop(success = true))
        assertTrue(state.recordFirstContentfulPaint())
        assertFalse(state.recordFirstContentfulPaint())
        assertTrue(state.rendered)
    }

    @Test
    fun `contentful paint followed by successful page stop becomes rendered once`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        assertFalse(state.recordFirstContentfulPaint())
        assertTrue(state.recordPageStop(success = true))
        assertFalse(state.recordPageStop(success = true))
        assertTrue(state.rendered)
    }

    @Test
    fun `failed page stop never qualifies an error page as rendered`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        assertFalse(state.recordFirstContentfulPaint())
        assertFalse(state.recordPageStop(success = false))
        assertFalse(state.rendered)
    }

    @Test
    fun `new requests and failures discard stale rendered state`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        state.recordPageStop(success = true)
        state.recordFirstContentfulPaint()
        assertTrue(state.rendered)

        state.recordRequest()
        state.recordPageStart()
        assertFalse(state.rendered)
        assertFalse(state.recordPageStop(success = true))

        state.recordFirstContentfulPaint()
        assertTrue(state.rendered)
        state.recordFailure()
        assertFalse(state.rendered)
    }

    @Test
    fun `fragment navigation retains a qualified document but cannot create qualification`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        state.recordPageStop(success = true)
        assertTrue(state.recordFirstContentfulPaint())

        state.recordFragmentOnlyRequest()
        assertTrue(state.rendered)

        state.recordRequest()
        state.recordFragmentOnlyRequest()
        assertFalse(state.rendered)
    }

    @Test
    fun `fragment navigation retains partial document progress until both load signals arrive`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        state.recordFragmentOnlyRequest()

        assertFalse(state.rendered)
        assertFalse(state.recordPageStop(success = true))
        assertTrue(state.recordFirstContentfulPaint())
    }

    @Test
    fun `fragment navigation retains completed document evidence across a paint reset`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        state.recordPageStop(success = true)
        assertTrue(state.recordFirstContentfulPaint())
        assertTrue(state.recordPaintStatusReset())

        state.recordFragmentOnlyRequest()
        assertTrue(state.recordFirstContentfulPaint())
    }

    @Test
    fun `callbacks before page start cannot qualify initial blank content`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        assertFalse(state.recordFirstContentfulPaint())
        assertFalse(state.recordPageStop(success = true))
        assertFalse(state.rendered)

        state.recordPageStart()
        assertFalse(state.recordPageStop(success = true))
        assertTrue(state.recordFirstContentfulPaint())
    }

    @Test
    fun `new page start discards both successful load and painted content`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        state.recordPageStop(success = true)
        assertTrue(state.recordFirstContentfulPaint())

        state.recordPageStart()
        assertFalse(state.rendered)
        assertFalse(state.recordFirstContentfulPaint())
        assertTrue(state.recordPageStop(success = true))
    }

    @Test
    fun `paint reset invalidates content but preserves a successful loaded page`() {
        val state = FrameWebRenderedState()

        state.recordRequest()
        state.recordPageStart()
        state.recordPageStop(success = true)
        assertTrue(state.recordFirstContentfulPaint())

        assertTrue(state.recordPaintStatusReset())
        assertFalse(state.rendered)
        assertFalse(state.recordPaintStatusReset())
        assertTrue(state.recordFirstContentfulPaint())
        assertFalse(state.recordFirstContentfulPaint())
    }
}
