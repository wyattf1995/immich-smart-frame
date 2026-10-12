package com.wyattfleming.frameos.web

/** Binds Photos readiness to the bridge generation that began the current document. */
class FrameWebPhotosReadinessState {
    private var expectedGeneration: Long? = null
    private var observedGeneration: Long? = null

    fun begin(expectedGeneration: Long?) {
        this.expectedGeneration = expectedGeneration
        observedGeneration = null
    }

    fun recordObservation(generation: Long): Boolean {
        if (expectedGeneration != generation) return false
        observedGeneration = generation
        return true
    }

    fun isReady(): Boolean = expectedGeneration != null && expectedGeneration == observedGeneration
}
