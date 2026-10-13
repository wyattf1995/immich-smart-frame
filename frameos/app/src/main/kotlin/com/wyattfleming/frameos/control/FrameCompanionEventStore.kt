package com.wyattfleming.frameos.control

import android.content.Context

object FrameCompanionEventPolicy {
    fun mayShow(id: String, recent: List<String>, lastShownAt: Long?, now: Long): Boolean =
        id !in recent && (lastShownAt == null || (now >= lastShownAt && now - lastShownAt >= 900_000L))
}

class FrameCompanionEventStore(context: Context) {
    enum class ClaimResult { CLAIMED, ALREADY_HANDLED, PERSISTENCE_FAILED }

    private val preferences = context.applicationContext.getSharedPreferences("frameos_companion_events", Context.MODE_PRIVATE)

    /**
     * Distinguishes an intentionally suppressed duplicate/rate-limited event from a failed
     * persistence write. Callers may accept [ClaimResult.ALREADY_HANDLED] as a terminal snapshot
     * outcome, but must retry [ClaimResult.PERSISTENCE_FAILED].
     */
    @Synchronized fun claimResult(id: String, nowMillis: Long): ClaimResult {
        val recent = preferences.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() }
        val last = preferences.getLong("last", 0L).takeIf { preferences.contains("last") }
        if (!FrameCompanionEventPolicy.mayShow(id, recent, last, nowMillis)) return ClaimResult.ALREADY_HANDLED
        return if (preferences.edit().putString("recent", (recent + id).takeLast(128).joinToString(","))
                .putLong("last", nowMillis).commit()) ClaimResult.CLAIMED else ClaimResult.PERSISTENCE_FAILED
    }

    /** Preserves legacy behavior: only a newly persisted claim returns true. */
    @Synchronized fun claim(id: String, nowMillis: Long): Boolean = claimResult(id, nowMillis) == ClaimResult.CLAIMED
}
