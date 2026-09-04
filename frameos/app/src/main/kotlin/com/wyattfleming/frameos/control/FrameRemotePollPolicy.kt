package com.wyattfleming.frameos.control

object FrameRemotePollPolicy {
    const val CONNECT_TIMEOUT_MILLIS = 3_000
    const val READ_TIMEOUT_MILLIS = 5_000
    const val DEFAULT_INTERVAL_MILLIS = 5_000L
    const val MAX_INTERVAL_MILLIS = 60_000L

    fun nextDelayMillis(success: Boolean, consecutiveFailures: Int, serverDelayMillis: Long?): Long =
        if (success) (serverDelayMillis ?: DEFAULT_INTERVAL_MILLIS).coerceIn(1_000L, MAX_INTERVAL_MILLIS)
        else (DEFAULT_INTERVAL_MILLIS * (1L shl consecutiveFailures.coerceIn(0, 3))).coerceAtMost(MAX_INTERVAL_MILLIS)
}
