package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.navigation.FrameMode

enum class FrameWebSlot(
    val isPersistent: Boolean,
    val canPreload: Boolean,
) {
    PHOTOS(isPersistent = true, canPreload = false),
    HOME_ASSISTANT(isPersistent = true, canPreload = true),
    CAMERAS(isPersistent = false, canPreload = false),
}

sealed interface FrameSurfaceTarget {
    data class Web(val slot: FrameWebSlot, val url: String) : FrameSurfaceTarget

    data object NativeWeather : FrameSurfaceTarget
}

class FrameSurfaceRouter(configuration: FrameConfiguration) {
    private val photosUrl = configuration.photosUrl
    private val homeAssistantWrapperUrl = HomeAssistantRoute.wrapperBaseUrl(
        homeAssistantUrl = configuration.homeAssistantUrl,
        version = WRAPPER_VERSION,
    )

    fun target(mode: FrameMode): FrameSurfaceTarget = when (mode) {
        FrameMode.PHOTOS -> FrameSurfaceTarget.Web(FrameWebSlot.PHOTOS, photosUrl)
        FrameMode.WEATHER -> FrameSurfaceTarget.NativeWeather
        FrameMode.HOME, FrameMode.CALENDAR -> FrameSurfaceTarget.Web(
            slot = FrameWebSlot.HOME_ASSISTANT,
            url = requireNotNull(HomeAssistantRoute.urlFor(homeAssistantWrapperUrl, mode)),
        )
        FrameMode.CAMERAS -> FrameSurfaceTarget.Web(
            slot = FrameWebSlot.CAMERAS,
            url = requireNotNull(HomeAssistantRoute.urlFor(homeAssistantWrapperUrl, mode)),
        )
    }

    fun calendarPreloadTarget(): FrameSurfaceTarget.Web = target(FrameMode.CALENDAR) as FrameSurfaceTarget.Web

    private companion object {
        const val WRAPPER_VERSION = "0e1f9606f205"
    }
}
