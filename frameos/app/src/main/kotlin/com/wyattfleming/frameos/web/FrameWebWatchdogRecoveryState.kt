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

    val requiresSessionRecreation: Boolean
        get() = consecutiveWatchdogTimeouts >= sessionRecreationThreshold

    fun recordWatchdogTimeout(): Boolean {
        consecutiveWatchdogTimeouts += 1
        return requiresSessionRecreation
    }

    /** Navigation alone cannot prove that the stuck session recovered. */
    fun recordNavigationStart() = Unit

    /** Called only after successful page stop and first contentful paint. */
    fun recordRendered() {
        consecutiveWatchdogTimeouts = 0
    }

    private companion object {
        const val SESSION_RECREATION_THRESHOLD = 3
    }
}
