package com.wyattfleming.frameos.web

data class FrameWebPageLoadArm(
    val generation: Long,
    val startedAtMillis: Long,
)

data class FrameWebPageLoadDisarm(
    val hadActiveLoad: Boolean,
    val timedOut: Boolean,
    val startedAtMillis: Long,
)

class FrameWebPageLoadWatchdogState {
    private var generation = 0L
    private var loading = false
    private var timedOutGeneration = -1L
    private var startedAtMillis = 0L

    fun arm(startedAtMillis: Long): FrameWebPageLoadArm {
        timedOutGeneration = -1L
        loading = true
        this.startedAtMillis = startedAtMillis
        generation += 1
        return FrameWebPageLoadArm(generation = generation, startedAtMillis = startedAtMillis)
    }

    fun markTimedOut(generation: Long): Long? {
        if (!loading || this.generation != generation) return null
        timedOutGeneration = generation
        loading = false
        return startedAtMillis
    }

    fun disarm(): FrameWebPageLoadDisarm {
        val result = FrameWebPageLoadDisarm(
            hadActiveLoad = loading,
            timedOut = timedOutGeneration == generation,
            startedAtMillis = startedAtMillis,
        )
        generation += 1
        loading = false
        timedOutGeneration = -1L
        startedAtMillis = 0L
        return result
    }
}
