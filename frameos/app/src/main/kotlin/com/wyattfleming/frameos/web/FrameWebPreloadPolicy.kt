package com.wyattfleming.frameos.web

internal object FrameWebPreloadPolicy {
    // The mounted Lenovo needed longer than the former 12-second window for a
    // cold HA Calendar load. Keep the headless session active long enough to
    // finish that bounded warm-up before it is attached to the display.
    const val CALENDAR_ACTIVE_MILLIS = 30_000L
}
