package com.wyattfleming.frameos.config

import com.wyattfleming.frameos.security.FrameUrlPolicy
import java.net.URI

data class FrameConfiguration(
    val photosUrl: String,
    val homeAssistantUrl: String,
    val weatherEntityId: String,
) {
    companion object {
        fun from(
            photosUrl: String,
            homeAssistantUrl: String,
            weatherEntityId: String = DEFAULT_WEATHER_ENTITY_ID,
            urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
        ): FrameConfiguration? {
            val normalizedPhotosUrl = photosUrl.trim()
            val normalizedHomeAssistantUrl = homeAssistantUrl.trim()
            val normalizedWeatherEntityId = weatherEntityId.trim()
            if (!urlPolicy.isSafeConfigurationUrl(normalizedPhotosUrl)) return null
            if (!urlPolicy.isSafeConfigurationUrl(normalizedHomeAssistantUrl)) return null
            if (!URI(normalizedHomeAssistantUrl).scheme.equals("https", ignoreCase = true)) return null
            if (!WEATHER_ENTITY_PATTERN.matches(normalizedWeatherEntityId)) return null

            return FrameConfiguration(
                photosUrl = normalizedPhotosUrl,
                homeAssistantUrl = normalizedHomeAssistantUrl,
                weatherEntityId = normalizedWeatherEntityId,
            )
        }

        private const val DEFAULT_WEATHER_ENTITY_ID = "weather.home"
        private val WEATHER_ENTITY_PATTERN = Regex("weather\\.[a-z0-9_]+")
    }
}
