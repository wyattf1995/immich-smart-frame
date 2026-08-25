package com.wyattfleming.frameos.web

data class FrameWebRetry(
    val attempt: Int,
    val delayMillis: Long,
)

class FrameWebRecoveryPolicy(
    private val initialDelayMillis: Long = INITIAL_DELAY_MILLIS,
    private val maximumDelayMillis: Long = MAXIMUM_DELAY_MILLIS,
) {
    init {
        require(initialDelayMillis > 0L)
        require(maximumDelayMillis >= initialDelayMillis)
    }

    fun nextRetry(afterAttempts: Int): FrameWebRetry {
        require(afterAttempts >= 0)
        val delay = initialDelayMillis * (1L shl afterAttempts.coerceAtMost(MAX_BACKOFF_SHIFT))
        val boundedDelay = delay.coerceAtMost(maximumDelayMillis)
        return FrameWebRetry(attempt = afterAttempts + 1, delayMillis = boundedDelay)
    }

    private companion object {
        const val INITIAL_DELAY_MILLIS = 1_000L
        const val MAXIMUM_DELAY_MILLIS = 30_000L
        const val MAX_BACKOFF_SHIFT = 20
    }
}
