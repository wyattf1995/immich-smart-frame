package com.wyattfleming.frameos.navigation

object FrameControlHints {
    fun forMode(mode: FrameMode, photosPaused: Boolean = false): String = when (mode) {
        FrameMode.PHOTOS -> if (photosPaused) "★ resumes · volume changes photo" else "★ pauses · volume changes photo"
        FrameMode.WEATHER -> "volume changes hours · ★ returns to photos"
        FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR, FrameMode.BIRDS ->
            "volume changes focus · ★ selects"
    }
}
