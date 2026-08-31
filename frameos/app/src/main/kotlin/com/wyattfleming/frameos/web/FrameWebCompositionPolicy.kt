package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.navigation.FrameMode

internal enum class FrameWebForeground {
    PRIMARY,
    HOME_ASSISTANT,
    NATIVE_WEATHER,
}

internal data class FrameWebComposition(
    val foreground: FrameWebForeground,
    val retainHomeAssistantSurface: Boolean,
    val warmCalendarInBackground: Boolean,
)

internal object FrameWebCompositionPolicy {
    fun forMode(mode: FrameMode): FrameWebComposition = when (mode) {
        FrameMode.PHOTOS -> FrameWebComposition(
            foreground = FrameWebForeground.PRIMARY,
            retainHomeAssistantSurface = true,
            warmCalendarInBackground = true,
        )
        FrameMode.HOME, FrameMode.CALENDAR -> FrameWebComposition(
            foreground = FrameWebForeground.HOME_ASSISTANT,
            retainHomeAssistantSurface = true,
            warmCalendarInBackground = false,
        )
        FrameMode.WEATHER -> FrameWebComposition(
            foreground = FrameWebForeground.NATIVE_WEATHER,
            retainHomeAssistantSurface = true,
            warmCalendarInBackground = true,
        )
        FrameMode.CAMERAS, FrameMode.BIRDS -> FrameWebComposition(
            foreground = FrameWebForeground.PRIMARY,
            retainHomeAssistantSurface = true,
            warmCalendarInBackground = true,
        )
    }
}
