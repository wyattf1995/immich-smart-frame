package com.wyattfleming.frameos.photos

class FramePhotoFallbackPolicy {
    private var failures = 0
    private var lastRecoveryAt: Long? = null
    private var recoveryDelayMillis = 60_000L
    var isShowing: Boolean = false
        private set

    fun recordFailure(photosVisible: Boolean, cachedPhotoAvailable: Boolean): Boolean {
        if (!photosVisible || !cachedPhotoAvailable) return false
        failures += 1
        isShowing = failures >= REQUIRED_FAILURES
        return isShowing
    }

    fun recordReachable() { failures = 0 }
    fun recordFreshPhoto() { failures = 0; isShowing = false; lastRecoveryAt = null; recoveryDelayMillis = 60_000L }
    fun hide() { isShowing = false }

    fun shouldRecover(lastPhotoAt: Long?, visibleSince: Long, now: Long, visible: Boolean, paused: Boolean): Boolean {
        if (!visible || paused) return false
        val evidence = maxOf(lastPhotoAt ?: visibleSince, visibleSince)
        if (!isShowing && now - evidence <= FramePhotoFreshnessPolicy.STALE_MILLIS) return false
        if (lastRecoveryAt?.let { now - it < recoveryDelayMillis } == true) return false
        if (lastRecoveryAt != null) recoveryDelayMillis = (recoveryDelayMillis * 2).coerceAtMost(300_000L)
        lastRecoveryAt = now
        return true
    }

    private companion object { const val REQUIRED_FAILURES = 3 }
}

object FramePhotoFreshnessPolicy {
    const val STALE_MILLIS = 180_000L
    fun isStale(lastPhotoAt: Long?, nowMillis: Long, photosVisible: Boolean, photosPaused: Boolean): Boolean =
        photosVisible && !photosPaused && lastPhotoAt != null && nowMillis - lastPhotoAt > STALE_MILLIS
}
