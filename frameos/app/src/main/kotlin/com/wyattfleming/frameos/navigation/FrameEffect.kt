package com.wyattfleming.frameos.navigation

sealed interface FrameEffect {
    data class AnnounceMode(val mode: FrameMode) : FrameEffect

    data class AnnounceMessage(val message: String) : FrameEffect
}
