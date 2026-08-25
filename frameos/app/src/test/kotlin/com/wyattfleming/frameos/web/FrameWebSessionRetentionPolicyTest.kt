package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebSessionRetentionPolicyTest {
    @Test
    fun `keeps a hidden Home Assistant session only until its adaptive ttl`() {
        val policy = FrameWebSessionRetentionPolicy(hiddenTtlMillis = 60_000L)

        assertFalse(policy.shouldEvictHiddenHome(nowMillis = 59_999L, hiddenSinceMillis = 0L))
        assertTrue(policy.shouldEvictHiddenHome(nowMillis = 60_000L, hiddenSinceMillis = 0L))
    }

    @Test
    fun `evicts hidden Home Assistant immediately at running low memory but never needs to retain cameras`() {
        val policy = FrameWebSessionRetentionPolicy(hiddenTtlMillis = 60_000L)

        assertTrue(policy.shouldEvictForTrimMemory(FrameWebSessionRetentionPolicy.TRIM_MEMORY_RUNNING_LOW))
        assertFalse(policy.shouldEvictForTrimMemory(FrameWebSessionRetentionPolicy.TRIM_MEMORY_UI_HIDDEN - 1))
        assertTrue(policy.camerasAreDisposable)
    }
}
