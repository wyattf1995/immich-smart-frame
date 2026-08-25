package com.wyattfleming.frameos.config

import com.wyattfleming.frameos.security.FrameUrlPolicy

data class FrameConfiguration(
    val photosUrl: String,
    val homeAssistantUrl: String,
) {
    companion object {
        fun from(
            photosUrl: String,
            homeAssistantUrl: String,
            urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
        ): FrameConfiguration? {
            val normalizedPhotosUrl = photosUrl.trim()
            val normalizedHomeAssistantUrl = homeAssistantUrl.trim()
            if (!urlPolicy.isSafeConfigurationUrl(normalizedPhotosUrl)) return null
            if (!urlPolicy.isSafeConfigurationUrl(normalizedHomeAssistantUrl)) return null

            return FrameConfiguration(
                photosUrl = normalizedPhotosUrl,
                homeAssistantUrl = normalizedHomeAssistantUrl,
            )
        }
    }
}
