package com.wyattfleming.frameos.navigation

/** Resolves optional modes without stranding frames provisioned by older builds. */
class FrameModeAvailability(
    birdsUrl: String?,
    requestedModes: List<FrameMode>? = null,
) {
    val cycleModes: List<FrameMode> = requestedModes
        ?.filter { it != FrameMode.BIRDS || !birdsUrl.isNullOrBlank() }
        ?.takeIf { it.isNotEmpty() }
        ?: FrameMode.cycleModes(birdsConfigured = !birdsUrl.isNullOrBlank())

    fun isAvailable(mode: FrameMode): Boolean = mode in cycleModes

    fun resolve(mode: FrameMode): FrameMode = if (isAvailable(mode)) mode else FrameMode.PHOTOS
}
