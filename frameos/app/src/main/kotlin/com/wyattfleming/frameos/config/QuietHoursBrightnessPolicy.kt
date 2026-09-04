package com.wyattfleming.frameos.config

import java.time.LocalTime

object QuietHoursBrightnessPolicy {
    /** Manual brightness always wins; auto brightness remains auto outside quiet hours. */
    fun effectivePercent(
        manualPercent: Int?,
        quietHours: QuietHours?,
        now: LocalTime,
    ): Int? = manualPercent ?: quietHours?.brightnessPercent?.takeIf { quietHours.isActiveAt(now) }
}
