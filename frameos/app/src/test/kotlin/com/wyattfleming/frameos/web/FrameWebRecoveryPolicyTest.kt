package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameWebRecoveryPolicyTest {
    @Test
    fun `continues exponential recovery at a bounded maximum interval`() {
        val policy = FrameWebRecoveryPolicy(initialDelayMillis = 1_000L, maximumDelayMillis = 30_000L)

        assertEquals(FrameWebRetry(attempt = 1, delayMillis = 1_000L), policy.nextRetry(afterAttempts = 0))
        assertEquals(FrameWebRetry(attempt = 2, delayMillis = 2_000L), policy.nextRetry(afterAttempts = 1))
        assertEquals(FrameWebRetry(attempt = 3, delayMillis = 4_000L), policy.nextRetry(afterAttempts = 2))
        assertEquals(FrameWebRetry(attempt = 6, delayMillis = 30_000L), policy.nextRetry(afterAttempts = 5))
        assertEquals(FrameWebRetry(attempt = 60, delayMillis = 30_000L), policy.nextRetry(afterAttempts = 59))
    }
}
