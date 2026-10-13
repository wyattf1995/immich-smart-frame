package com.wyattfleming.frameos.control

import android.content.Context

object FrameCompanionEventPolicy {
    fun mayShow(id: String, recent: List<String>, lastShownAt: Long?, now: Long): Boolean =
        id !in recent && (lastShownAt == null || (now >= lastShownAt && now - lastShownAt >= 900_000L))
}

class FrameCompanionEventStore private constructor(private val persistence: Persistence) {
    data class State(val recent: List<String> = emptyList(), val lastShownAt: Long? = null)

    interface Persistence {
        fun read(): State
        fun write(state: State): Boolean
    }

    enum class ClaimResult { CLAIMED, ALREADY_HANDLED, PERSISTENCE_FAILED }

    constructor(context: Context) : this(object : Persistence {
        private val preferences = context.applicationContext.getSharedPreferences("frameos_companion_events", Context.MODE_PRIVATE)

        override fun read(): State = State(
            recent = preferences.getString("recent", "").orEmpty().split(',').filter { it.isNotBlank() },
            lastShownAt = preferences.getLong("last", 0L).takeIf { preferences.contains("last") },
        )

        override fun write(state: State): Boolean = preferences.edit()
            .putString("recent", state.recent.takeLast(MAX_RECENT).joinToString(","))
            .putLong("last", requireNotNull(state.lastShownAt))
            .commit()
    })

    /**
     * Distinguishes an intentionally suppressed duplicate/rate-limited event from a failed
     * persistence write. Callers may accept [ClaimResult.ALREADY_HANDLED] as a terminal snapshot
     * outcome, but must retry [ClaimResult.PERSISTENCE_FAILED].
     */
    @Synchronized fun claimResult(id: String, nowMillis: Long): ClaimResult {
        val state = persistence.read()
        if (!FrameCompanionEventPolicy.mayShow(id, state.recent, state.lastShownAt, nowMillis)) {
            return ClaimResult.ALREADY_HANDLED
        }
        val next = State((state.recent + id).takeLast(MAX_RECENT), nowMillis)
        return if (persistence.write(next)) ClaimResult.CLAIMED else ClaimResult.PERSISTENCE_FAILED
    }

    /** Preserves legacy behavior: only a newly persisted claim returns true. */
    @Synchronized fun claim(id: String, nowMillis: Long): Boolean = claimResult(id, nowMillis) == ClaimResult.CLAIMED

    companion object {
        private const val MAX_RECENT = 128
        internal fun forTest(persistence: Persistence): FrameCompanionEventStore = FrameCompanionEventStore(persistence)
    }
}
