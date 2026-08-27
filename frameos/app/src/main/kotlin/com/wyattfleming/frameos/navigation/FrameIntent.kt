package com.wyattfleming.frameos.navigation

sealed interface FrameIntent {
    data object NextMode : FrameIntent

    data object PreviousMode : FrameIntent

    data object PrimaryAction : FrameIntent

    data object GoHome : FrameIntent

    data object BrightnessUp : FrameIntent

    data object BrightnessDown : FrameIntent

    data object RestoreAutoBrightness : FrameIntent

    data object IdleExpired : FrameIntent
}
