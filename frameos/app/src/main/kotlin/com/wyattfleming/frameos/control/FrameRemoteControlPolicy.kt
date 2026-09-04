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

    fun acceptsDeviceId(deviceId: String): Boolean = deviceId.matches(DEVICE_ID_PATTERN)

    fun acceptsCommandId(commandId: String): Boolean = commandId.matches(COMMAND_ID_PATTERN)

    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,80}")
    private val COMMAND_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
