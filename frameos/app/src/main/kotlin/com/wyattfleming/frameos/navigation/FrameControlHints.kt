package com.wyattfleming.frameos.navigation

object FrameControlHints {
    fun forMode(mode: FrameMode, photosPaused: Boolean = false): String = when (mode) {
        FrameMode.PHOTOS -> if (photosPaused) "★ resumes · volume changes photo" else "★ pauses · volume changes photo"
        // Weather shows all 24 hours at once and Ocean is one page, so volume has nothing to page.
        FrameMode.WEATHER, FrameMode.OCEAN -> "★ returns to photos"
        FrameMode.ART, FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR, FrameMode.BIRDS ->
            "volume changes focus · ★ selects"
    }
}
