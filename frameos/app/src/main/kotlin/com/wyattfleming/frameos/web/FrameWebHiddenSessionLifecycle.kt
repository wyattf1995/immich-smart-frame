package com.wyattfleming.frameos.web

class FrameWebHiddenSessionLifecycle {
    var hiddenSinceMillis: Long? = null
        private set
    private var preloadGeneration = 0

    fun onHomePreloaded(nowMillis: Long): Int {
        if (hiddenSinceMillis == null) hiddenSinceMillis = nowMillis
        return ++preloadGeneration
    }

    fun onHomeHidden(nowMillis: Long) {
        if (hiddenSinceMillis == null) hiddenSinceMillis = nowMillis
    }

    fun onHomeShown() {
        hiddenSinceMillis = null
    }

    fun onNonHomeShown() = Unit

    fun onEvicted() {
        hiddenSinceMillis = null
        preloadGeneration += 1
    }

    fun isCurrentPreload(generation: Int): Boolean = generation == preloadGeneration
}
