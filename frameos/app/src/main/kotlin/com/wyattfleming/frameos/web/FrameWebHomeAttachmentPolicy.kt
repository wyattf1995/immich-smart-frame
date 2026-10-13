package com.wyattfleming.frameos.web

/**
 * Controls whether a device can keep the Home Assistant session attached while another
 * surface is displayed. The default preserves the established warm-session behavior.
 */
internal class FrameWebHomeAttachmentPolicy(
    private val detachHiddenHomeAssistant: Boolean = false,
) {
    fun detachWarmHomeWhenShowing(slot: FrameWebSlot): Boolean =
        detachHiddenHomeAssistant && slot != FrameWebSlot.HOME_ASSISTANT

    fun allowsBackgroundPreload(slot: FrameWebSlot): Boolean =
        !(detachHiddenHomeAssistant && slot == FrameWebSlot.HOME_ASSISTANT)
}
