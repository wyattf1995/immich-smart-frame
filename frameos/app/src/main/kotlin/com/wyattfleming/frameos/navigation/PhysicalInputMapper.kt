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
            KeyEvent.KEYCODE_MOVE_HOME -> FrameIntent.GoHome
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> FrameIntent.PrimaryAction
            KeyEvent.KEYCODE_DPAD_RIGHT -> FrameIntent.NextMode
            KeyEvent.KEYCODE_DPAD_LEFT -> FrameIntent.PreviousMode
            KEYCODE_LENOVO_GESTURE_LEFT -> FrameIntent.NextMode
            KEYCODE_LENOVO_GESTURE_RIGHT -> FrameIntent.PreviousMode
            KeyEvent.KEYCODE_VOLUME_UP -> FrameIntent.NextMode
            KeyEvent.KEYCODE_VOLUME_DOWN -> FrameIntent.PreviousMode
            else -> null
        }
    }

    private companion object {
        const val SCAN_GESTURE_NEXT = 251
        const val SCAN_GESTURE_PREVIOUS = 252

        // Lenovo's Generic.kl maps scan codes 251/252 to OEM key codes 291/292.
        const val KEYCODE_LENOVO_GESTURE_LEFT = 291
        const val KEYCODE_LENOVO_GESTURE_RIGHT = 292
    }
}
