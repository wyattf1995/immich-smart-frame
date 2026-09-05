package com.wyattfleming.frameos.web

/** Holds only the latest Photos navigation until its current bridge attachment is ready. */
internal class FramePhotoLoadGate {
    private var attachmentEpoch = 0L
    private var readyEpoch: Long? = null
    private var failedEpoch: Long? = null
    private var pendingUrl: String? = null

    fun beginAttachment(): Long {
        attachmentEpoch += 1
        readyEpoch = null
        failedEpoch = null
        pendingUrl = null
        return attachmentEpoch
    }

    fun invalidate() {
        attachmentEpoch += 1
        readyEpoch = null
        failedEpoch = null
        pendingUrl = null
    }

    fun isReady(): Boolean = readyEpoch == attachmentEpoch

    fun isFailed(): Boolean = failedEpoch == attachmentEpoch

    fun defer(url: String) {
        pendingUrl = url
    }

    fun markReady(epoch: Long): Boolean {
        if (epoch != attachmentEpoch) return false
        readyEpoch = epoch
        failedEpoch = null
        return true
    }

    fun markFailed(epoch: Long): Boolean {
        if (epoch != attachmentEpoch) return false
        failedEpoch = epoch
        return true
    }

    fun takePendingIfReady(epoch: Long): String? {
        if (readyEpoch != epoch || epoch != attachmentEpoch) return null
        val pending = pendingUrl
        pendingUrl = null
        return pending
    }

    fun dropPending(): Boolean {
        val hadPending = pendingUrl != null
        pendingUrl = null
        return hadPending
    }
}
