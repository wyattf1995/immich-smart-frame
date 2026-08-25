package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.navigation.FrameMode
import java.net.URI

object HomeAssistantRoute {
    fun wrapperBaseUrl(homeAssistantUrl: String, version: String): String {
        require(VERSION_PATTERN.matches(version)) { "Unsafe wall-panel version" }
        val configured = URI(homeAssistantUrl)
        require(configured.isAbsolute && configured.host != null) { "An absolute Home Assistant URL is required" }
        return URI(
            configured.scheme,
            null,
            configured.host,
            configured.port,
            WRAPPER_PATH,
            "v=$version",
            null,
        ).toASCIIString()
    }

    fun urlFor(baseUrl: String, mode: FrameMode): String? {
        val fragment = when (mode) {
            FrameMode.HOME -> "home"
            FrameMode.WEATHER -> return null
            FrameMode.CAMERAS -> "cameras"
            FrameMode.CALENDAR -> "calendar"
            FrameMode.PHOTOS -> return null
        }

        return "${baseUrl.substringBefore('#')}#$fragment"
    }

    private const val WRAPPER_PATH = "/local/frameos-panel.html"
    private val VERSION_PATTERN = Regex("[A-Za-z0-9._-]+")
}
