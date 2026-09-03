package com.wyattfleming.frameos.web

class FrameWebRenderedState {
    private var pageStopSucceeded = false
    private var contentfulPaintSeen = false
    var rendered = false
        private set

    fun recordRequest() {
        reset()
    }

    fun recordFailure() {
        reset()
    }

    fun recordPageStop(success: Boolean): Boolean {
        if (!success) {
            reset()
            return false
        }
        pageStopSucceeded = true
        return updateRendered()
    }

    fun recordFirstContentfulPaint(): Boolean {
        contentfulPaintSeen = true
        return updateRendered()
    }

    private fun updateRendered(): Boolean {
        if (rendered || !pageStopSucceeded || !contentfulPaintSeen) return false
        rendered = true
        return true
    }

    private fun reset() {
        pageStopSucceeded = false
        contentfulPaintSeen = false
        rendered = false
    }
}
