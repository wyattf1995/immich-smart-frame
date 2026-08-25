package com.wyattfleming.frameos.web

data class FrameWebRetry(
    val attempt: Int,
    val delayMillis: Long,
)

class FrameWebRecoveryPolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val initialDelayMillis: Long = INITIAL_DELAY_MILLIS,
) {
    init {
        require(maxAttempts > 0)
        require(initialDelayMillis > 0L)
    }

    fun nextRetry(afterAttempts: Int): FrameWebRetry? {
        require(afterAttempts >= 0)
        if (afterAttempts >= maxAttempts) return null
        val delay = initialDelayMillis * (1L shl afterAttempts.coerceAtMost(MAX_BACKOFF_SHIFT))
        return FrameWebRetry(attempt = afterAttempts + 1, delayMillis = delay)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val INITIAL_DELAY_MILLIS = 1_000L
        const val MAX_BACKOFF_SHIFT = 20
    }
}
