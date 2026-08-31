package com.wyattfleming.frameos.config

object FrameConfigurationPatch {
    fun withBirdsUrl(existing: FrameConfiguration, birdsUrl: String?): FrameConfiguration? =
        FrameConfiguration.from(
            photosUrl = existing.photosUrl,
            homeAssistantUrl = existing.homeAssistantUrl,
            weatherEntityId = existing.weatherEntityId,
            homeAssistantFallbackUrl = existing.homeAssistantFallbackUrl,
            birdsUrl = birdsUrl,
        )
}
