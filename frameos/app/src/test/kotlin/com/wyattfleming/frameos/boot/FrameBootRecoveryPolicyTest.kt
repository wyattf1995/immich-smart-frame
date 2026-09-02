package com.wyattfleming.frameos.boot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBootRecoveryPolicyTest {
    @Test
    fun `boot and package replacement launch immediately with bounded retries`() {
        val expected = listOf(0L, 15_000L, 60_000L)

        assertEquals(expected, FrameBootRecoveryPolicy.launchDelaysFor(ACTION_BOOT_COMPLETED))
        assertEquals(expected, FrameBootRecoveryPolicy.launchDelaysFor(ACTION_MY_PACKAGE_REPLACED))
    }

    @Test
    fun `untrusted broadcasts cannot schedule frame recovery`() {
        listOf(null, "", "android.intent.action.SCREEN_ON", "com.example.RETRY").forEach { action ->
            assertTrue(FrameBootRecoveryPolicy.launchDelaysFor(action).isEmpty())
        }
    }

    private companion object {
        const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    }
}
