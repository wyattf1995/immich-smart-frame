package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.navigation.FrameMode
import java.net.URI

object HomeAssistantRoute {
    fun wrapperBaseUrl(homeAssistantUrl: String, version: String): String {
        require(VERSION_PATTERN.matches(version)) { "Unsafe wall-panel version" }
        val configured = URI(homeAssistantUrl)
        require(configured.isAbsolute && configured.host != null) { "An absolute Home Assistant URL is required" }
        val touchscreenRoute = configured.rawPath == TOUCHSCREEN_ROUTE || configured.rawPath == "$TOUCHSCREEN_ROUTE/"
        return URI(
            configured.scheme,
            null,
            configured.host,
            configured.port,
            if (touchscreenRoute) TOUCHSCREEN_WRAPPER_PATH else WRAPPER_PATH,
            "v=${if (touchscreenRoute) TOUCHSCREEN_WRAPPER_VERSION else version}",
            null,
        ).toASCIIString()
    }

    fun urlFor(baseUrl: String, mode: FrameMode): String? {
        val fragment = when (mode) {
            FrameMode.HOME -> "home"
            FrameMode.WEATHER -> return null
            FrameMode.CAMERAS -> "cameras"
            FrameMode.CALENDAR -> "calendar"
            FrameMode.BIRDS -> return null
            FrameMode.PHOTOS -> return null
        }

        return "${baseUrl.substringBefore('#')}#$fragment"
    }

    private const val WRAPPER_PATH = "/local/frameos-panel.html"
    private const val TOUCHSCREEN_ROUTE = "/frameos-touchscreen"
    private const val TOUCHSCREEN_WRAPPER_PATH = "/local/frameos-touchscreen-panel.html"
    private const val TOUCHSCREEN_WRAPPER_VERSION = "d9c1b399af73"
    private val VERSION_PATTERN = Regex("[A-Za-z0-9._-]+")
}
