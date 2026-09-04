package com.wyattfleming.frameos.photos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePhotoFallbackPolicyTest {
    @Test fun `fallback rejects photo observations older than the healthy probe`() {
        val policy = FramePhotoFallbackPolicy()
        repeat(3) { policy.recordFailure(true, true) }
        assertFalse(policy.canDismiss(100, null))
        assertFalse(policy.canDismiss(100, 101))
        assertTrue(policy.canDismiss(102, 101))
    }

    @Test fun `reachable probe resets consecutive failures but does not prove a photo recovered`() {
        val policy = FramePhotoFallbackPolicy()
        repeat(2) { policy.recordFailure(true, true) }
        policy.recordReachable()
        assertFalse(policy.recordFailure(true, true))
        repeat(2) { policy.recordFailure(true, true) }
        policy.recordReachable()
        assertTrue(policy.isShowing)
        policy.recordFreshPhoto()
        assertFalse(policy.isShowing)
    }

    @Test fun `missing first photo recovers after deadline with bounded backoff and excludes hidden pause`() {
        val policy = FramePhotoFallbackPolicy()
        assertFalse(policy.shouldRecover(null, 1_000, 180_999, true, false))
        assertFalse(policy.shouldRecover(null, 1_000, 181_001, false, false))
        assertFalse(policy.shouldRecover(null, 1_000, 181_001, true, true))
        assertTrue(policy.shouldRecover(null, 1_000, 181_001, true, false))
        assertFalse(policy.shouldRecover(null, 1_000, 200_000, true, false))
        assertTrue(policy.shouldRecover(null, 1_000, 241_001, true, false))
        policy.recordFreshPhoto()
        assertFalse(policy.shouldRecover(241_001, 1_000, 250_000, true, false))
    }

    @Test fun `requires three visible photo failures and a cached image`() {
        val policy = FramePhotoFallbackPolicy()
        assertFalse(policy.recordFailure(photosVisible = true, cachedPhotoAvailable = true))
        assertFalse(policy.recordFailure(photosVisible = true, cachedPhotoAvailable = true))
        assertTrue(policy.recordFailure(photosVisible = true, cachedPhotoAvailable = true))
        policy.recordFreshPhoto()
        assertFalse(policy.isShowing)
    }

    @Test fun `stale detection excludes hidden and paused photos`() {
        assertTrue(FramePhotoFreshnessPolicy.isStale(1, 181_001, photosVisible = true, photosPaused = false))
        assertFalse(FramePhotoFreshnessPolicy.isStale(1, 181_001, photosVisible = false, photosPaused = false))
        assertFalse(FramePhotoFreshnessPolicy.isStale(1, 181_001, photosVisible = true, photosPaused = true))
    }
}
