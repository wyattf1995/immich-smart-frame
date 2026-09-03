package com.wyattfleming.frameos.web

class FrameWebRenderedState {
    private var pageStarted = false
    private var pageStopSucceeded = false
    private var contentfulPaintSeen = false
    var rendered = false
        private set

    fun recordRequest() {
        reset()
    }

    fun recordPageStart() {
        reset()
        pageStarted = true
    }

    fun recordFailure() {
        reset()
    }

    fun recordPageStop(success: Boolean): Boolean {
        if (!success) {
            reset()
            return false
        }
        if (!pageStarted) return false
        pageStopSucceeded = true
        return updateRendered()
    }

    fun recordFirstContentfulPaint(): Boolean {
        if (!pageStarted) return false
        contentfulPaintSeen = true
        return updateRendered()
    }

    fun recordPaintStatusReset(): Boolean {
        val invalidatedRenderedContent = rendered
        contentfulPaintSeen = false
        rendered = false
        return invalidatedRenderedContent
    }

    private fun updateRendered(): Boolean {
        if (rendered || !pageStopSucceeded || !contentfulPaintSeen) return false
        rendered = true
        return true
    }

    private fun reset() {
        pageStarted = false
        pageStopSucceeded = false
        contentfulPaintSeen = false
        rendered = false
    }
}
