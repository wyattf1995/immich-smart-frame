package com.wyattfleming.frameos.web

internal object FrameWebSessionDisposal {
    fun prepare(
        clearFocus: () -> Unit,
        deactivate: () -> Unit,
        stop: () -> Unit,
    ): Boolean {
        var clean = true

        fun attempt(operation: () -> Unit) {
            runCatching(operation).onFailure { clean = false }
        }

        attempt(clearFocus)
        attempt(deactivate)
        attempt(stop)
        return clean
    }
}
