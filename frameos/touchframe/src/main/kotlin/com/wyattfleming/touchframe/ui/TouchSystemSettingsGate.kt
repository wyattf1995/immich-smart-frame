package com.wyattfleming.touchframe.ui

/**
 * Android Settings can turn off Wi-Fi, change the date (which breaks the Home Assistant sign-in) or remove this app,
 * and a frame left like that looks like a power cut. A tap on the wall's Settings tab only explains; a press and hold
 * warns, and only confirming that warning opens Settings.
 */
class TouchSystemSettingsGate(
    private val notice: (Notice) -> Unit,
    private val open: () -> Boolean,
) {
    enum class Tone { INFO, WARNING }

    data class Notice(
        val message: String,
        val tone: Tone,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
    )

    fun tap() = notice(Notice(HOLD_HINT, Tone.INFO))

    fun hold() = notice(Notice(WARNING, Tone.WARNING, OPEN_LABEL) {
        if (!open()) notice(Notice(RETURN_BUTTON_OFF, Tone.WARNING))
    })

    private companion object {
        const val HOLD_HINT = "Press and hold Settings to open Android settings."
        const val WARNING = "Android settings can turn off Wi-Fi, change the date or remove this app."
        const val OPEN_LABEL = "Open"
        const val RETURN_BUTTON_OFF = "Turn on the FrameOS Touch return button before opening Android Settings."
    }
}
