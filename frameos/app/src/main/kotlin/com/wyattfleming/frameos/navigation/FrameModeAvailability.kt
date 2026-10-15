package com.wyattfleming.frameos.navigation

/** Resolves optional modes without stranding frames provisioned by older builds. */
class FrameModeAvailability(
    birdsUrl: String?,
    artUrl: String? = null,
    requestedModes: List<FrameMode>? = null,
) {
    val cycleModes: List<FrameMode> = requestedModes
        ?.filter {
            (it != FrameMode.BIRDS || !birdsUrl.isNullOrBlank()) &&
                (it != FrameMode.ART || !artUrl.isNullOrBlank())
        }
        ?.takeIf { it.isNotEmpty() }
        ?.let { modes ->
            if (artUrl.isNullOrBlank() || FrameMode.ART in modes) modes
            else modes.toMutableList().apply { add(indexOf(FrameMode.PHOTOS) + 1, FrameMode.ART) }
        }
        // The companion's mode order predates Ocean and never names it. A frame that cycles every household mode (the
        // living room) gets Ocean after Weather; a hand-picked short list (the parents' Photos and Weather) stays as chosen.
        ?.let { modes ->
            val household = requestedModes.orEmpty().containsAll(listOf(FrameMode.HOME, FrameMode.WEATHER, FrameMode.CAMERAS, FrameMode.CALENDAR))
            if (FrameMode.OCEAN in modes || !household) modes
            else modes.toMutableList().apply { add(indexOf(FrameMode.WEATHER) + 1, FrameMode.OCEAN) }
        }
        ?: FrameMode.cycleModes(
            birdsConfigured = !birdsUrl.isNullOrBlank(),
            artConfigured = !artUrl.isNullOrBlank(),
        )

    fun isAvailable(mode: FrameMode): Boolean = mode in cycleModes

    fun resolve(mode: FrameMode): FrameMode = if (isAvailable(mode)) mode else FrameMode.PHOTOS
}
