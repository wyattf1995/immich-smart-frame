package com.wyattfleming.frameos.navigation

import android.view.KeyEvent

sealed interface ContextualFrameAction {
    data class PhotoStep(val forward: Boolean) : ContextualFrameAction
    data class WeatherPageStep(val forward: Boolean) : ContextualFrameAction
    data class ForwardToWeb(val keyCode: Int) : ContextualFrameAction
    data object PrimaryAction : ContextualFrameAction
}

class ContextualInputMapper {
    fun map(mode: FrameMode, keyCode: Int, shiftPressed: Boolean): ContextualFrameAction? {
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            return when (mode) {
                FrameMode.PHOTOS -> ContextualFrameAction.PhotoStep(forward = !shiftPressed)
                FrameMode.WEATHER -> ContextualFrameAction.WeatherPageStep(forward = !shiftPressed)
                FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR ->
                    ContextualFrameAction.ForwardToWeb(KeyEvent.KEYCODE_TAB)
            }
        }

        if (mode in HOME_ASSISTANT_MODES) {
            return when (keyCode) {
                KeyEvent.KEYCODE_STAR -> ContextualFrameAction.ForwardToWeb(KeyEvent.KEYCODE_ENTER)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> ContextualFrameAction.ForwardToWeb(keyCode)
                else -> null
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> ContextualFrameAction.PrimaryAction
            else -> null
        }
    }

    private companion object {
        val HOME_ASSISTANT_MODES = setOf(FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR)
    }
}
