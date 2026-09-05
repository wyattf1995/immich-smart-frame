package com.wyattfleming.frameos.config

import com.wyattfleming.frameos.security.FrameUrlPolicy
import java.net.URI

data class FrameConfiguration(
    val photosUrl: String,
    val homeAssistantUrl: String,
    val weatherEntityId: String,
    val homeAssistantFallbackUrl: String? = null,
    val birdsUrl: String? = null,
) {
    companion object {
        fun from(
            photosUrl: String,
            homeAssistantUrl: String,
            weatherEntityId: String = DEFAULT_WEATHER_ENTITY_ID,
            homeAssistantFallbackUrl: String? = null,
            birdsUrl: String? = null,
            urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
        ): FrameConfiguration? {
            val normalizedPhotosUrl = photosUrl.trim()
            val normalizedHomeAssistantUrl = homeAssistantUrl.trim()
            val normalizedWeatherEntityId = weatherEntityId.trim()
            val normalizedFallbackUrl = homeAssistantFallbackUrl?.trim()?.ifBlank { null }
            val normalizedBirdsUrl = birdsUrl?.trim()?.ifBlank { null }
            if (!urlPolicy.isSafeConfigurationUrl(normalizedPhotosUrl)) return null
            if (!urlPolicy.isSafeConfigurationUrl(normalizedHomeAssistantUrl)) return null
            if (!URI(normalizedHomeAssistantUrl).scheme.equals("https", ignoreCase = true)) return null
            if (normalizedFallbackUrl != null) {
                if (!urlPolicy.isSafeConfigurationUrl(normalizedFallbackUrl)) return null
                if (!URI(normalizedFallbackUrl).scheme.equals("https", ignoreCase = true)) return null
            }
            if (normalizedBirdsUrl != null && !urlPolicy.isSafeConfigurationUrl(normalizedBirdsUrl)) return null
            if (!WEATHER_ENTITY_PATTERN.matches(normalizedWeatherEntityId)) return null

            return FrameConfiguration(
                photosUrl = normalizedPhotosUrl,
                homeAssistantUrl = normalizedHomeAssistantUrl,
                weatherEntityId = normalizedWeatherEntityId,
                homeAssistantFallbackUrl = normalizedFallbackUrl,
                birdsUrl = normalizedBirdsUrl,
            )
        }

        private const val DEFAULT_WEATHER_ENTITY_ID = "weather.home"
        private val WEATHER_ENTITY_PATTERN = Regex("weather\\.[a-z0-9_]+")
    }
}
