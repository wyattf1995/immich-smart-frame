package com.wyattfleming.frameos.diagnostics

import android.content.Context
import com.wyattfleming.frameos.navigation.FrameMode

/** A bounded, asynchronous device-local record for post-reboot diagnosis. */
class FrameDiagnosticStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @Synchronized fun recordMode(mode: FrameMode) = edit { putString(MODE, mode.name) }
    @Synchronized fun recordPaint(epochMillis: Long) = edit { putLong(LAST_PAINT, epochMillis) }
    @Synchronized fun recordWeatherSuccess(epochMillis: Long) = edit { putLong(LAST_WEATHER, epochMillis) }
    @Synchronized fun recordRecovery(error: String) = edit {
        putInt(RECOVERY_COUNT, preferences.getInt(RECOVERY_COUNT, 0).coerceAtMost(MAX_RECOVERIES - 1) + 1)
        putString(LAST_ERROR, error.take(MAX_ERROR_LENGTH))
    }

    @Synchronized fun snapshot(): FrameDiagnosticSnapshot = FrameDiagnosticSnapshot(
        currentMode = preferences.getString(MODE, null)?.let { runCatching { FrameMode.valueOf(it) }.getOrNull() } ?: FrameMode.PHOTOS,
        lastPaintEpochMillis = preferences.getLong(LAST_PAINT, 0L),
        lastWeatherSuccessEpochMillis = preferences.getLong(LAST_WEATHER, 0L),
        recoveryCount = preferences.getInt(RECOVERY_COUNT, 0).coerceIn(0, MAX_RECOVERIES),
        lastError = preferences.getString(LAST_ERROR, null),
    )

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply()
    }

    private companion object {
        const val NAME = "frameos_diagnostics"
        const val MODE = "mode"
        const val LAST_PAINT = "last_paint"
        const val LAST_WEATHER = "last_weather"
        const val RECOVERY_COUNT = "recovery_count"
        const val LAST_ERROR = "last_error"
        const val MAX_RECOVERIES = 10_000
        const val MAX_ERROR_LENGTH = 120
    }
}
