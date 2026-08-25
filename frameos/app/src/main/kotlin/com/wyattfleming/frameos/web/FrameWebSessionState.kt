package com.wyattfleming.frameos.web

class FrameWebSessionState {
    private var requestedUrl: String? = null
    private var lastSuccessfulUrl: String? = null
    private var dirty = false

    fun shouldRequest(url: String): Boolean = dirty || requestedUrl != url

    fun recordRequest(url: String) {
        requestedUrl = url
        dirty = false
    }

    fun recordSuccess() {
        lastSuccessfulUrl = requestedUrl
    }

    fun recordFailure() {
        dirty = true
    }

    fun recoveryUrl(): String? = requestedUrl ?: lastSuccessfulUrl
}
