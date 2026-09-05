package com.wyattfleming.frameos.web

class FrameWebSessionState {
    private var requestedUrl: String? = null
    private var lastSuccessfulUrl: String? = null
    private var dirty = false

    fun shouldRequest(url: String): Boolean = dirty || requestedUrl != url

    fun isExactFragmentOnlyRequest(url: String): Boolean {
        val current = requestedUrl ?: return false
        val currentFragment = current.indexOf('#')
        val nextFragment = url.indexOf('#')
        return currentFragment >= 0 &&
            nextFragment >= 0 &&
            current.substring(0, currentFragment) == url.substring(0, nextFragment) &&
            current.substring(currentFragment + 1) != url.substring(nextFragment + 1)
    }

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
