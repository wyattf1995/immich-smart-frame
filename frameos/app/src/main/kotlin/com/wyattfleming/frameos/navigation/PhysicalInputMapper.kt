package com.wyattfleming.frameos.navigation

import android.view.KeyEvent

class PhysicalInputMapper {
    fun mapKeyDown(
        keyCode: Int,
        scanCode: Int,
        isLongPress: Boolean = false,
    ): FrameIntent? {
        if (scanCode == SCAN_GESTURE_NEXT) return FrameIntent.NextMode
        if (scanCode == SCAN_GESTURE_PREVIOUS) return FrameIntent.PreviousMode

        return when (keyCode) {
            KeyEvent.KEYCODE_STAR -> if (isLongPress) FrameIntent.GoHome else FrameIntent.PrimaryAction
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> FrameIntent.PrimaryAction
            KeyEvent.KEYCODE_VOLUME_UP -> FrameIntent.BrightnessUp
            KeyEvent.KEYCODE_VOLUME_DOWN -> FrameIntent.BrightnessDown
            else -> null
        }
    }

    private companion object {
        const val SCAN_GESTURE_NEXT = 251
        const val SCAN_GESTURE_PREVIOUS = 252
    }
}
