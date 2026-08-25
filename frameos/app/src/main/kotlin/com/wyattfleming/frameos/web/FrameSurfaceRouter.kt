package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.navigation.FrameMode

enum class FrameWebSlot {
    PHOTOS,
    HOME_ASSISTANT,
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
        FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR -> FrameSurfaceTarget.Web(
            slot = FrameWebSlot.HOME_ASSISTANT,
            url = requireNotNull(HomeAssistantRoute.urlFor(homeAssistantWrapperUrl, mode)),
        )
    }

    fun homeAssistantRestingUrl(): String =
        requireNotNull(HomeAssistantRoute.urlFor(homeAssistantWrapperUrl, FrameMode.HOME))

    private companion object {
        const val WRAPPER_VERSION = "20260824-frameos1"
    }
}
