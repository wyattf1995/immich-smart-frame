package com.wyattfleming.frameos.web

/** Keeps watchdog failures distinct from ordinary navigation until Gecko has rendered again. */
class FrameWebWatchdogRecoveryState(
    private val sessionRecreationThreshold: Int = SESSION_RECREATION_THRESHOLD,
) {
    init {
        require(sessionRecreationThreshold > 0)
    }

    var consecutiveWatchdogTimeouts = 0
        private set
    private var recreationPending = false

    val requiresSessionRecreation: Boolean
        get() = recreationPending

    fun recordWatchdogTimeout(): Boolean {
        consecutiveWatchdogTimeouts += 1
        if (consecutiveWatchdogTimeouts % sessionRecreationThreshold == 0) {
            recreationPending = true
        }
        return requiresSessionRecreation
    }

    /** Navigation alone cannot prove that the stuck session recovered. */
    fun recordNavigationStart() = Unit

    /** Called only after successful page stop and first contentful paint. */
    fun recordRendered() {
        consecutiveWatchdogTimeouts = 0
        recreationPending = false
    }

    /** A fresh session is already open; wait for the next threshold before replacing it again. */
    fun markSessionRecreated() {
        recreationPending = false
    }

    private companion object {
        const val SESSION_RECREATION_THRESHOLD = 3
    }
}
