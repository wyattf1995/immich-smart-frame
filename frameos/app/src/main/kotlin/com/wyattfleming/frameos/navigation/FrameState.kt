package com.wyattfleming.frameos.navigation

data class FrameState(
    val mode: FrameMode = FrameMode.PHOTOS,
    val photosPaused: Boolean = false,
    val brightnessOverridePercent: Int? = null,
)
