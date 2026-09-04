package com.wyattfleming.frameos.diagnostics

import com.wyattfleming.frameos.navigation.FrameMode

data class FrameDiagnosticSnapshot(
    val currentMode: FrameMode = FrameMode.PHOTOS,
    val lastPaintEpochMillis: Long = 0L,
    val lastWeatherSuccessEpochMillis: Long = 0L,
    val recoveryCount: Int = 0,
    val lastError: String? = null,
)
