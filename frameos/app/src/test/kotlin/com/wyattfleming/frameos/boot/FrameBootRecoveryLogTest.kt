package com.wyattfleming.frameos.boot

import com.wyattfleming.frameos.FrameOperationLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reboot or an update must leave a record of what brought the frame back: before these lines a recovery that worked
 * logged nothing, so a frame on screen after a power cut could not be told apart from one the Home intent restored.
 */
class FrameBootRecoveryLogTest {
    @Test
    fun `the trigger is named by what the system sent`() {
        assertEquals(
            FrameOperationLogEntry("boot_recovery", "scheduled", "boot_completed", 23_000L),
            FrameBootRecoveryLog.scheduled("android.intent.action.BOOT_COMPLETED", sinceBootMillis = 23_000L),
        )
        assertEquals(
            FrameOperationLogEntry("boot_recovery", "scheduled", "package_replaced", 4_000_000L),
            FrameBootRecoveryLog.scheduled("android.intent.action.MY_PACKAGE_REPLACED", sinceBootMillis = 4_000_000L),
        )
    }

    @Test
    fun `the first launch and the alarm retries are told apart, with every outcome`() {
        assertEquals(
            FrameOperationLogEntry("boot_recovery", "launch", "started", 23_100L),
            FrameBootRecoveryLog.launched(retry = false, FrameBootRecoveryLog.Launch.STARTED, sinceBootMillis = 23_100L),
        )
        assertEquals(
            FrameOperationLogEntry("boot_recovery", "retry", "skipped_no_overlay_access", 38_000L),
            FrameBootRecoveryLog.launched(retry = true, FrameBootRecoveryLog.Launch.NO_OVERLAY_ACCESS, sinceBootMillis = 38_000L),
        )
        assertEquals(
            FrameOperationLogEntry("boot_recovery", "retry", "failed", 83_000L),
            FrameBootRecoveryLog.launched(retry = true, FrameBootRecoveryLog.Launch.FAILED, sinceBootMillis = 83_000L),
        )
    }

    @Test
    fun `the rendered draw that cancels the retries is logged`() {
        assertEquals(
            FrameOperationLogEntry("boot_recovery", "confirmed", "rendered", 41_000L),
            FrameBootRecoveryLog.confirmed(sinceBootMillis = 41_000L),
        )
    }
}
