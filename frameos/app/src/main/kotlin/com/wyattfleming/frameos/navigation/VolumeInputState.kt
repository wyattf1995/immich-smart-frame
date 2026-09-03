package com.wyattfleming.frameos.navigation

enum class VolumeButton {
    UP,
    DOWN,
}

sealed interface VolumeInputEffect {
    data class Step(val forward: Boolean) : VolumeInputEffect
    data object RestoreAutoBrightness : VolumeInputEffect
}

class VolumeInputState {
    private var upPressed = false
    private var downPressed = false
    private var chordActive = false

    fun onDown(button: VolumeButton, repeatCount: Int): VolumeInputEffect? {
        if (repeatCount != 0 || isPressed(button)) return null
        setPressed(button, pressed = true)
        if (!chordActive && upPressed && downPressed) {
            chordActive = true
            return VolumeInputEffect.RestoreAutoBrightness
        }
        return null
    }

    fun onUp(button: VolumeButton): VolumeInputEffect? {
        if (!isPressed(button)) return null
        val wasChordActive = chordActive
        setPressed(button, pressed = false)
        if (!upPressed && !downPressed) chordActive = false
        return if (wasChordActive) {
            null
        } else {
            VolumeInputEffect.Step(forward = button == VolumeButton.UP)
        }
    }

    fun reset() {
        upPressed = false
        downPressed = false
        chordActive = false
    }

    private fun isPressed(button: VolumeButton): Boolean = when (button) {
        VolumeButton.UP -> upPressed
        VolumeButton.DOWN -> downPressed
    }

    private fun setPressed(button: VolumeButton, pressed: Boolean) {
        when (button) {
            VolumeButton.UP -> upPressed = pressed
            VolumeButton.DOWN -> downPressed = pressed
        }
    }
}
