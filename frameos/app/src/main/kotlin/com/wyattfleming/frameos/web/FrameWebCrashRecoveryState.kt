package com.wyattfleming.frameos.web

/** Tracks when Gecko has closed a content session and a new generation is required. */
class FrameWebCrashRecoveryState {
    var generation: Int = 0
        private set
    var requiresReopen: Boolean = false
        private set
    val canUseSession: Boolean
        get() = !requiresReopen

    fun markClosedByContentProcess() {
        requiresReopen = true
    }

    fun reopenIfRequired(openSession: () -> Unit): Boolean {
        if (!requiresReopen) return false
        openSession()
        generation += 1
        requiresReopen = false
        return true
    }
}
