package com.wyattfleming.frameos.config

/** Device-local opt-ins: companion experience updates must not replace these. */
data class FrameTouchscreenSettings(
    val dashboardEnabled: Boolean = false,
    val detachHiddenHome: Boolean = false,
    val startWithPhotos: Boolean = false,
) {
    fun showsDashboard(hasTouchscreen: Boolean): Boolean = dashboardEnabled && hasTouchscreen
}
