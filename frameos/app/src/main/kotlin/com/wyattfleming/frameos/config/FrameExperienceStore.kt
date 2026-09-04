package com.wyattfleming.frameos.config

import android.content.Context
import com.wyattfleming.frameos.navigation.FrameMode
import java.time.LocalTime

class FrameExperienceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): FrameExperienceSettings = runCatching {
        val modes = preferences.getString(MODES, null)
            ?.split(',')
            ?.map(FrameMode::valueOf)
            ?.takeIf { it.isNotEmpty() }
            ?: FrameExperienceSettings.DEFAULT_MODES
        val start = preferences.getString(QUIET_START, null)?.let(LocalTime::parse)
        val end = preferences.getString(QUIET_END, null)?.let(LocalTime::parse)
        val brightness = preferences.getInt(QUIET_BRIGHTNESS, -1)
        FrameExperienceSettings(
            orderedEnabledModes = modes,
            quietHours = if (start != null && end != null && brightness in 10..100) QuietHours(start, end, brightness) else null,
            deactivateHiddenHomeAssistant = preferences.getBoolean(DEACTIVATE_HIDDEN_HOME, false),
            idleReturnSeconds = preferences.getInt(IDLE_RETURN_SECONDS, FrameExperienceSettings.DEFAULT_IDLE_RETURN_SECONDS),
            eventOverlaysEnabled = preferences.getBoolean(EVENT_OVERLAYS_ENABLED, false),
            settingsRevision = preferences.getLong(SETTINGS_REVISION, FrameExperienceSettings.NO_SETTINGS_REVISION),
        )
    }.getOrDefault(FrameExperienceSettings())

    fun write(settings: FrameExperienceSettings) {
        preferences.edit()
            .putString(MODES, settings.orderedEnabledModes.joinToString(",") { it.name })
            .putString(QUIET_START, settings.quietHours?.start?.toString())
            .putString(QUIET_END, settings.quietHours?.end?.toString())
            .putInt(QUIET_BRIGHTNESS, settings.quietHours?.brightnessPercent ?: -1)
            .putBoolean(DEACTIVATE_HIDDEN_HOME, settings.deactivateHiddenHomeAssistant)
            .putInt(IDLE_RETURN_SECONDS, settings.idleReturnSeconds)
            .putBoolean(EVENT_OVERLAYS_ENABLED, settings.eventOverlaysEnabled)
            .putLong(SETTINGS_REVISION, settings.settingsRevision)
            .apply()
    }

    private companion object {
        const val NAME = "frameos_experience"
        const val MODES = "ordered_modes"
        const val QUIET_START = "quiet_start"
        const val QUIET_END = "quiet_end"
        const val QUIET_BRIGHTNESS = "quiet_brightness"
        const val DEACTIVATE_HIDDEN_HOME = "deactivate_hidden_home"
        const val IDLE_RETURN_SECONDS = "idle_return_seconds"
        const val EVENT_OVERLAYS_ENABLED = "event_overlays_enabled"
        const val SETTINGS_REVISION = "settings_revision"
    }
}
