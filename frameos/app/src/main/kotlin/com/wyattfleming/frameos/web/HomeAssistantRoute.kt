package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.navigation.FrameMode

object HomeAssistantRoute {
    fun urlFor(baseUrl: String, mode: FrameMode): String? {
        val fragment = when (mode) {
            FrameMode.HOME -> "home"
            FrameMode.CAMERAS -> "cameras"
            FrameMode.CALENDAR -> "calendar"
            FrameMode.PHOTOS -> return null
        }

        return "${baseUrl.substringBefore('#')}#$fragment"
    }
}
