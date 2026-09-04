package com.wyattfleming.frameos.config

import java.time.LocalTime

object QuietHoursBrightnessPolicy {
    /** Manual brightness always wins; auto brightness remains auto outside quiet hours. */
    fun effectivePercent(
        manualPercent: Int?,
        quietHours: QuietHours?,
        now: LocalTime,
    ): Int? = manualPercent ?: quietHours?.brightnessPercent?.takeIf { quietHours.isActiveAt(now) }

    /** Reach the next boundary promptly, and recheck wall-clock changes at least once a minute. */
    fun nextRefreshDelayMillis(quietHours: QuietHours?, now: LocalTime): Long? {
        if (quietHours == null) return null
        val dayNanos = 86_400_000_000_000L
        val nextBoundaryNanos = listOf(quietHours.start, quietHours.end).minOf { boundary ->
            val delay = Math.floorMod(boundary.toNanoOfDay() - now.toNanoOfDay(), dayNanos)
            if (delay == 0L) dayNanos else delay
        }
        return (nextBoundaryNanos / 1_000_000L).coerceIn(1L, 60_000L)
    }
}
