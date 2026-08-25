package com.wyattfleming.frameos.navigation

data class FrameTransition(
    val state: FrameState,
    val effects: List<FrameEffect> = emptyList(),
)

class FrameReducer {
    fun reduce(state: FrameState, intent: FrameIntent): FrameTransition = when (intent) {
        FrameIntent.NextMode -> state.changeMode(state.mode.next())
        FrameIntent.PreviousMode -> state.changeMode(state.mode.previous())
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

    private fun FrameMode.next(): FrameMode =
        FrameMode.entries[(ordinal + 1) % FrameMode.entries.size]

    private fun FrameMode.previous(): FrameMode =
        FrameMode.entries[(ordinal - 1 + FrameMode.entries.size) % FrameMode.entries.size]

    private companion object {
        const val MIN_BRIGHTNESS_PERCENT = 10
        const val MAX_BRIGHTNESS_PERCENT = 100
        const val DEFAULT_BRIGHTNESS_PERCENT = 50
        const val BRIGHTNESS_STEP_PERCENT = 10
    }
}
