package com.wyattfleming.frameos.web

internal object FrameWebPreloadPolicy {
    // The mounted Lenovo needed longer than the former 12-second window for a
    // cold HA Calendar load. Keep its attached background surface active long
    // enough to finish that bounded render before suspending the session.
    const val CALENDAR_ACTIVE_MILLIS = 30_000L
}
