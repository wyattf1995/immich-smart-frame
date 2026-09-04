package com.wyattfleming.frameos.photos

class FramePhotoFallbackPolicy {
    private var failures = 0
    var isShowing: Boolean = false
        private set

    fun recordFailure(photosVisible: Boolean, cachedPhotoAvailable: Boolean): Boolean {
        if (!photosVisible || !cachedPhotoAvailable) return false
        failures += 1
        isShowing = failures >= REQUIRED_FAILURES
        return isShowing
    }

    fun recordFreshPhoto() { failures = 0; isShowing = false }
    fun hide() { isShowing = false }

    private companion object { const val REQUIRED_FAILURES = 3 }
}

object FramePhotoFreshnessPolicy {
    const val STALE_MILLIS = 180_000L
    fun isStale(lastPhotoAt: Long?, nowMillis: Long, photosVisible: Boolean, photosPaused: Boolean): Boolean =
        photosVisible && !photosPaused && lastPhotoAt != null && nowMillis - lastPhotoAt > STALE_MILLIS
}
