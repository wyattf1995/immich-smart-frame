package com.wyattfleming.frameos.control

import android.content.Context

class FrameCompanionEventStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("frameos_companion_events", Context.MODE_PRIVATE)

    @Synchronized fun claim(id: String, nowMillis: Long): Boolean {
        if (preferences.contains("id:$id") || nowMillis - preferences.getLong("last", Long.MIN_VALUE) < MIN_INTERVAL_MILLIS) return false
        preferences.edit().putBoolean("id:$id", true).putLong("last", nowMillis).apply()
        return true
    }

    private companion object { const val MIN_INTERVAL_MILLIS = 15 * 60 * 1_000L }
}
