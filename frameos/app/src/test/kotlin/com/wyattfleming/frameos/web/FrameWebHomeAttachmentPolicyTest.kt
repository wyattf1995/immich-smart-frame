package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebHomeAttachmentPolicyTest {
    @Test
    fun `default preserves the warm Home attachment and preload behavior`() {
        val policy = FrameWebHomeAttachmentPolicy()

        assertFalse(policy.detachWarmHomeWhenShowing(FrameWebSlot.PHOTOS))
        assertTrue(policy.allowsBackgroundPreload(FrameWebSlot.HOME_ASSISTANT))
    }

    @Test
    fun `opt in detaches hidden Home and rejects its background preload`() {
        val policy = FrameWebHomeAttachmentPolicy(detachHiddenHomeAssistant = true)

        assertTrue(policy.detachWarmHomeWhenShowing(FrameWebSlot.PHOTOS))
        assertTrue(policy.detachWarmHomeWhenShowing(FrameWebSlot.CAMERAS))
        assertFalse(policy.detachWarmHomeWhenShowing(FrameWebSlot.HOME_ASSISTANT))
        assertFalse(policy.allowsBackgroundPreload(FrameWebSlot.HOME_ASSISTANT))
    }
}
