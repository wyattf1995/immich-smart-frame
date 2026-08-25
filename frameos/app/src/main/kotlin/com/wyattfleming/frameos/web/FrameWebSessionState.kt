package com.wyattfleming.frameos.web

class FrameWebSessionState {
    private var requestedUrl: String? = null
    private var dirty = false

    fun shouldRequest(url: String): Boolean = dirty || requestedUrl != url

    fun recordRequest(url: String) {
        requestedUrl = url
        dirty = false
    }

    fun recordFailure() {
        dirty = true
    }
}
