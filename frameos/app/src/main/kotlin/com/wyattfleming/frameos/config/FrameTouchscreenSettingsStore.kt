package com.wyattfleming.frameos.config

import android.content.Context

class FrameTouchscreenSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("frameos_touchscreen", Context.MODE_PRIVATE)

    fun read() = FrameTouchscreenSettings(
        dashboardEnabled = preferences.getBoolean("dashboard_enabled", false),
        detachHiddenHome = preferences.getBoolean("detach_hidden_home", false),
    )

    fun write(settings: FrameTouchscreenSettings) {
        preferences.edit()
            .putBoolean("dashboard_enabled", settings.dashboardEnabled)
            .putBoolean("detach_hidden_home", settings.detachHiddenHome)
            .apply()
    }
}
