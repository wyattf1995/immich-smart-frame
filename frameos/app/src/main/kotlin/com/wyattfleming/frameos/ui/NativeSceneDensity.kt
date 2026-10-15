package com.wyattfleming.frameos.ui

/**
 * The shared Weather page and Ocean are laid out for a panel at least 800 dp tall (the 1280×800 frame). The Lenovo
 * reports 1920×1080 at 320 dpi, only 540 dp, so they render at a lower density that gives them that height.
 */
object NativeSceneDensity {
    const val PANEL_HEIGHT_DP = 800

    /** The density to render the shared screens at, or null when the screen already has the height. Rounds down, so never below it. */
    fun dpiFor(heightPx: Int, heightDp: Int): Int? =
        if (heightPx <= 0 || heightDp <= 0 || heightDp >= PANEL_HEIGHT_DP) null else heightPx * DEFAULT_DPI / PANEL_HEIGHT_DP

    private const val DEFAULT_DPI = 160
}
