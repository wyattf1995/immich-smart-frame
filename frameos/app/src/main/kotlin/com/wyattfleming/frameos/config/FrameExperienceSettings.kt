package com.wyattfleming.frameos.config

import com.wyattfleming.frameos.navigation.FrameMode
import java.time.LocalTime

/**
 * Device-local presentation preferences. They deliberately contain no endpoint
 * or credential material, so a future trusted control plane can update them
 * without widening the URL configuration surface.
 */
data class FrameExperienceSettings(
    val orderedEnabledModes: List<FrameMode> = DEFAULT_MODES,
    val quietHours: QuietHours? = null,
    val deactivateHiddenHomeAssistant: Boolean = false,
) {
    init {
        require(orderedEnabledModes.isNotEmpty())
        require(orderedEnabledModes.first() == FrameMode.PHOTOS) { "Photos must remain first" }
        require(orderedEnabledModes.distinct().size == orderedEnabledModes.size)
    }

    companion object {
        val DEFAULT_MODES = listOf(
            FrameMode.PHOTOS,
            FrameMode.HOME,
            FrameMode.WEATHER,
            FrameMode.CAMERAS,
            FrameMode.CALENDAR,
        )
        val PARENTS_PHOTOS_WEATHER = FrameExperienceSettings(
            orderedEnabledModes = listOf(FrameMode.PHOTOS, FrameMode.WEATHER),
        )
    }
}

data class QuietHours(
    val start: LocalTime,
    val end: LocalTime,
    val brightnessPercent: Int,
) {
    init {
        require(brightnessPercent in 10..100)
        require(start != end) { "Quiet hours must span a non-zero interval" }
    }

    fun isActiveAt(time: LocalTime): Boolean = if (start < end) {
        time >= start && time < end
    } else {
        time >= start || time < end
    }
}
