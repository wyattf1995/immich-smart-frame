package com.wyattfleming.frameos.photos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePhotoAttachmentEpochTest {
    @Test
    fun `same session reconfigure invalidates only the earlier attachment epoch`() {
        val epochs = FramePhotoAttachmentEpoch()

        val first = epochs.begin()
        val second = epochs.begin()

        assertFalse(epochs.isCurrent(first))
        assertTrue(epochs.isCurrent(second))
    }

    @Test
    fun `lifecycle invalidation rejects a previously ready attachment epoch`() {
        val epochs = FramePhotoAttachmentEpoch()
        val attached = epochs.begin()

        epochs.invalidate()

        assertFalse(epochs.isCurrent(attached))
    }
}
