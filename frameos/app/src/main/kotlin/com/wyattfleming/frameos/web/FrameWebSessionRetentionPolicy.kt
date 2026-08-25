package com.wyattfleming.frameos.web

import android.content.ComponentCallbacks2

class FrameWebSessionRetentionPolicy(
    private val hiddenTtlMillis: Long = HIDDEN_HOME_TTL_MILLIS,
) {
    init {
        require(hiddenTtlMillis >= 0L)
    }

    val camerasAreDisposable: Boolean = true

    fun shouldEvictHiddenHome(nowMillis: Long, hiddenSinceMillis: Long): Boolean =
        nowMillis - hiddenSinceMillis >= hiddenTtlMillis

    fun shouldEvictForTrimMemory(level: Int): Boolean =
        level in TRIM_MEMORY_RUNNING_LOW..TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND

    companion object {
        const val TRIM_MEMORY_UI_HIDDEN = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        const val TRIM_MEMORY_RUNNING_LOW = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        const val TRIM_MEMORY_RUNNING_CRITICAL = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        const val TRIM_MEMORY_BACKGROUND = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        const val HIDDEN_HOME_TTL_MILLIS = 5 * 60 * 1_000L
    }
}
