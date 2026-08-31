package com.wyattfleming.frameos.navigation

data class FrameTransition(
    val state: FrameState,
    val effects: List<FrameEffect> = emptyList(),
)

class FrameReducer(
    private val availableModes: () -> List<FrameMode> = { FrameMode.cycleModes(birdsConfigured = false) },
) {
    fun reduce(state: FrameState, intent: FrameIntent): FrameTransition = when (intent) {
        FrameIntent.NextMode -> state.changeMode(state.mode.next(availableModes()))
        FrameIntent.PreviousMode -> state.changeMode(state.mode.previous(availableModes()))
        FrameIntent.GoHome -> state.changeMode(FrameMode.HOME)
        FrameIntent.IdleExpired -> state
            .copy(mode = FrameMode.PHOTOS, photosPaused = false)
            .announceMode()
        FrameIntent.PrimaryAction -> state.primaryAction()
        FrameIntent.BrightnessUp -> state.changeBrightness(BRIGHTNESS_STEP_PERCENT)
        FrameIntent.BrightnessDown -> state.changeBrightness(-BRIGHTNESS_STEP_PERCENT)
        FrameIntent.RestoreAutoBrightness -> FrameTransition(
            state = state.copy(brightnessOverridePercent = null),
            effects = listOf(FrameEffect.AnnounceMessage("Automatic brightness")),
        )
    }

    private fun FrameState.primaryAction(): FrameTransition {
        if (mode != FrameMode.PHOTOS) {
            return copy(mode = FrameMode.PHOTOS, photosPaused = false).announceMode()
        }

        val paused = !photosPaused
        return FrameTransition(
            state = copy(photosPaused = paused),
            effects = listOf(
                FrameEffect.AnnounceMessage(if (paused) "Photos paused" else "Photos resumed"),
            ),
        )
    }

    private fun FrameState.changeMode(newMode: FrameMode): FrameTransition =
        copy(mode = newMode).announceMode()

    private fun FrameState.announceMode(): FrameTransition = FrameTransition(
        state = this,
        effects = listOf(FrameEffect.AnnounceMode(mode)),
    )

    private fun FrameState.changeBrightness(deltaPercent: Int): FrameTransition {
        val percent = ((brightnessOverridePercent ?: DEFAULT_BRIGHTNESS_PERCENT) + deltaPercent)
            .coerceIn(MIN_BRIGHTNESS_PERCENT, MAX_BRIGHTNESS_PERCENT)
        return FrameTransition(
            state = copy(brightnessOverridePercent = percent),
            effects = listOf(FrameEffect.AnnounceMessage("Brightness $percent%")),
        )
    }

    private fun FrameMode.next(modes: List<FrameMode>): FrameMode {
        val index = modes.indexOf(this).takeIf { it >= 0 } ?: 0
        return modes[(index + 1) % modes.size]
    }

    private fun FrameMode.previous(modes: List<FrameMode>): FrameMode {
        val index = modes.indexOf(this).takeIf { it >= 0 } ?: 0
        return modes[(index - 1 + modes.size) % modes.size]
    }

    private companion object {
        const val MIN_BRIGHTNESS_PERCENT = 10
        const val MAX_BRIGHTNESS_PERCENT = 100
        const val DEFAULT_BRIGHTNESS_PERCENT = 50
        const val BRIGHTNESS_STEP_PERCENT = 10
    }
}
