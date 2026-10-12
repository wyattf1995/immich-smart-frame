package com.wyattfleming.frameos.navigation

/** Touch media keeps its viewport while the navigation rail overlays it. */
internal class TouchMediaNavigation {
    private var navigationEnabled = false
    private var dashboardVisible = false
    private var mode = FrameMode.PHOTOS
    private var menuOpen = false

    val immersiveMedia: Boolean
        get() = navigationEnabled && !dashboardVisible && (mode == FrameMode.PHOTOS || mode == FrameMode.ART)
    val contentHasRailInset: Boolean get() = navigationEnabled && !immersiveMedia
    val railVisible: Boolean get() = navigationEnabled && (!immersiveMedia || menuOpen)
    val dismissLayerVisible: Boolean get() = immersiveMedia && menuOpen

    fun setScreen(navigationEnabled: Boolean, dashboardVisible: Boolean, mode: FrameMode) {
        if (this.navigationEnabled != navigationEnabled || this.dashboardVisible != dashboardVisible || this.mode != mode) {
            dismissMenu()
        }
        this.navigationEnabled = navigationEnabled
        this.dashboardVisible = dashboardVisible
        this.mode = mode
    }

    fun acceptsEdgeSwipe(direction: ModeGestureDirection): Boolean =
        !navigationEnabled || (immersiveMedia && !menuOpen && direction == ModeGestureDirection.PREVIOUS)

    fun revealMenu() {
        if (immersiveMedia) menuOpen = true
    }

    fun dismissMenu() {
        menuOpen = false
    }
}
