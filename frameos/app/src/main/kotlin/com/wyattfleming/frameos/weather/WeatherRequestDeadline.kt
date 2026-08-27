package com.wyattfleming.frameos.weather

class WeatherRequestDeadline(
    startNanos: Long = System.nanoTime(),
    timeoutMillis: Long,
) {
    private val expiresAtNanos = startNanos + timeoutMillis * NANOS_PER_MILLISECOND

    init {
        require(timeoutMillis > 0L)
    }

    fun remainingMillis(nowNanos: Long = System.nanoTime()): Long =
        ((expiresAtNanos - nowNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)

    fun isExpired(nowNanos: Long = System.nanoTime()): Boolean = nowNanos >= expiresAtNanos

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
