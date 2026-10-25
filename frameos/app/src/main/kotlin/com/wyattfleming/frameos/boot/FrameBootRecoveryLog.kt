package com.wyattfleming.frameos.boot

import com.wyattfleming.frameos.FrameOperationLogEntry

/**
 * One `FrameOS` line per recovery step (`route=boot_recovery`), so a reboot or an update leaves a record of what
 * brought the frame back. elapsedMs is time since boot, which places each step on the boot timeline.
 */
internal object FrameBootRecoveryLog {
    enum class Launch(val outcome: String) {
        STARTED("started"),
        NO_OVERLAY_ACCESS("skipped_no_overlay_access"),
        FAILED("failed"),
    }

    fun scheduled(action: String?, sinceBootMillis: Long): FrameOperationLogEntry =
        entry("scheduled", if (action == ACTION_MY_PACKAGE_REPLACED) "package_replaced" else "boot_completed", sinceBootMillis)

    fun launched(retry: Boolean, result: Launch, sinceBootMillis: Long): FrameOperationLogEntry =
        entry(if (retry) "retry" else "launch", result.outcome, sinceBootMillis)

    fun confirmed(sinceBootMillis: Long): FrameOperationLogEntry = entry("confirmed", "rendered", sinceBootMillis)

    private fun entry(stage: String, outcome: String, sinceBootMillis: Long) =
        FrameOperationLogEntry(ROUTE, stage, outcome, sinceBootMillis)

    private const val ROUTE = "boot_recovery"
    private const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
}
