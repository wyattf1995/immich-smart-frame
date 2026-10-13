package com.wyattfleming.touchframe.control

/** Companion snapshots may be retried or arrive after locally accepted settings. */
internal object TouchRemoteSettingsPolicy {
    fun shouldApply(currentRevision: Long, incomingRevision: Long): Boolean =
        incomingRevision >= 0 && incomingRevision > currentRevision
}
