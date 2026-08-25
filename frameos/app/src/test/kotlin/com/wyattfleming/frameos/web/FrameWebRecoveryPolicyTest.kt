package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameWebRecoveryPolicyTest {
    @Test
    fun `uses a bounded exponential retry schedule`() {
        val policy = FrameWebRecoveryPolicy(maxAttempts = 3, initialDelayMillis = 1_000L)

        assertEquals(FrameWebRetry(attempt = 1, delayMillis = 1_000L), policy.nextRetry(afterAttempts = 0))
        assertEquals(FrameWebRetry(attempt = 2, delayMillis = 2_000L), policy.nextRetry(afterAttempts = 1))
        assertEquals(FrameWebRetry(attempt = 3, delayMillis = 4_000L), policy.nextRetry(afterAttempts = 2))
        assertNull(policy.nextRetry(afterAttempts = 3))
    }
}
