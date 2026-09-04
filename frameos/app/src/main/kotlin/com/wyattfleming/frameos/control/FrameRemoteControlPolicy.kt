package com.wyattfleming.frameos.control

/** Pure validation boundary for commands received from the configured companion. */
object FrameRemoteControlPolicy {
    const val MAX_COMMAND_TTL_MILLIS = 60_000L
    const val MAX_HOLD_DURATION_SECONDS = 3_600L
    private val profilePattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    fun acceptsCommand(issuedAtMillis: Long, expiresAtMillis: Long, nowMillis: Long): Boolean =
        issuedAtMillis >= 0L &&
            expiresAtMillis >= issuedAtMillis &&
            expiresAtMillis - issuedAtMillis <= MAX_COMMAND_TTL_MILLIS &&
            nowMillis <= expiresAtMillis

    fun acceptsProfile(profile: String): Boolean = profilePattern.matches(profile)
}
