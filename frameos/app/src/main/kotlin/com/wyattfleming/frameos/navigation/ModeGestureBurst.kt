package com.wyattfleming.frameos.navigation

internal enum class ModeGestureDirection {
    NEXT,
    PREVIOUS,
}

internal data class ModeGestureUpdate(
    val targetMode: FrameMode,
    val commitDelayMillis: Long,
)

internal class ModeGestureBurst(
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
    private val maxBurstMillis: Long = DEFAULT_MAX_BURST_MILLIS,
    private val maxEventAgeMillis: Long = DEFAULT_MAX_EVENT_AGE_MILLIS,
) {
    private var pendingTarget: FrameMode? = null
    private var burstStartedAtMillis: Long? = null

    init {
        require(settleMillis >= 0) { "settleMillis must not be negative" }
        require(maxBurstMillis >= 0) { "maxBurstMillis must not be negative" }
        require(maxEventAgeMillis >= 0) { "maxEventAgeMillis must not be negative" }
    }

    fun offer(
        displayedMode: FrameMode,
        direction: ModeGestureDirection,
        eventTimeMillis: Long,
        receivedAtMillis: Long,
        repeatCount: Int,
        availableModes: List<FrameMode>,
    ): ModeGestureUpdate? {
        if (
            repeatCount != 0 ||
            availableModes.isEmpty() ||
            eventTimeMillis > receivedAtMillis ||
            receivedAtMillis - eventTimeMillis > maxEventAgeMillis
        ) {
            return null
        }

        val baseMode = pendingTarget ?: displayedMode
        val baseIndex = availableModes.indexOf(baseMode).takeIf { it >= 0 } ?: 0
        val targetIndex = when (direction) {
            ModeGestureDirection.NEXT -> (baseIndex + 1) % availableModes.size
            ModeGestureDirection.PREVIOUS -> (baseIndex - 1 + availableModes.size) % availableModes.size
        }
        val targetMode = availableModes[targetIndex]
        val startedAt = burstStartedAtMillis ?: receivedAtMillis.also {
            burstStartedAtMillis = it
        }
        pendingTarget = targetMode

        val settleDeadline = saturatedAdd(receivedAtMillis, settleMillis)
        val maxDeadline = saturatedAdd(startedAt, maxBurstMillis)
        val commitDeadline = minOf(settleDeadline, maxDeadline)
        return ModeGestureUpdate(
            targetMode = targetMode,
            commitDelayMillis = (commitDeadline - receivedAtMillis).coerceAtLeast(0L),
        )
    }

    fun consumeTarget(): FrameMode? = pendingTarget.also { cancel() }

    fun cancel() {
        pendingTarget = null
        burstStartedAtMillis = null
    }

    private fun saturatedAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private companion object {
        const val DEFAULT_SETTLE_MILLIS = 240L
        const val DEFAULT_MAX_BURST_MILLIS = 900L
        const val DEFAULT_MAX_EVENT_AGE_MILLIS = 500L
    }
}
