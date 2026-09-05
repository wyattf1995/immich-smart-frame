package com.wyattfleming.frameos.photos

/** Separate from capture generation: extension registration may outlive pause transitions. */
internal class FramePhotoAttachmentEpoch {
    private var value = 0L

    fun begin(): Long = ++value

    fun invalidate() {
        value += 1
    }

    fun current(): Long = value

    fun isCurrent(epoch: Long): Boolean = epoch == value
}
