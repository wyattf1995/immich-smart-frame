package com.wyattfleming.frameos.photos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePhotoFallbackPolicyTest {
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
