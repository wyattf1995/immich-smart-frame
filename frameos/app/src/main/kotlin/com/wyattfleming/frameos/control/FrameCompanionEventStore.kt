package com.wyattfleming.frameos.control

import android.content.Context

object FrameCompanionEventPolicy {
    fun mayShow(id: String, recent: List<String>, lastShownAt: Long?, now: Long): Boolean =
        id !in recent && (lastShownAt == null || (now >= lastShownAt && now - lastShownAt >= 900_000L))
}

class FrameCompanionEventStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("frameos_companion_events", Context.MODE_PRIVATE)
    @Synchronized fun claim(id: String, nowMillis: Long): Boolean {
        val recent = preferences.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }
        val last = preferences.getLong("last", 0L).takeIf { preferences.contains("last") }
        if (!FrameCompanionEventPolicy.mayShow(id, recent, last, nowMillis)) return false
        return preferences.edit().putString("recent", (recent + id).takeLast(128).joinToString(","))
            .putLong("last", nowMillis).commit()
    }
}
