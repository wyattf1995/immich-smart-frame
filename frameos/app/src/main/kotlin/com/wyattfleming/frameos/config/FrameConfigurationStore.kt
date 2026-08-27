package com.wyattfleming.frameos.config

import android.content.Context

class FrameConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): FrameConfiguration? {
        val photosUrl = preferences.getString(KEY_PHOTOS_URL, null) ?: return null
        val homeAssistantUrl = preferences.getString(KEY_HOME_ASSISTANT_URL, null) ?: return null
        val weatherEntityId = preferences.getString(KEY_WEATHER_ENTITY_ID, null) ?: return null
        return FrameConfiguration.from(
            photosUrl,
            homeAssistantUrl,
            weatherEntityId,
            preferences.getString(KEY_HOME_ASSISTANT_FALLBACK_URL, null),
        )
    }

    fun write(configuration: FrameConfiguration) {
        preferences.edit()
            .putString(KEY_PHOTOS_URL, configuration.photosUrl)
            .putString(KEY_HOME_ASSISTANT_URL, configuration.homeAssistantUrl)
            .putString(KEY_WEATHER_ENTITY_ID, configuration.weatherEntityId)
            .putString(KEY_HOME_ASSISTANT_FALLBACK_URL, configuration.homeAssistantFallbackUrl)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "frameos_configuration"
        const val KEY_PHOTOS_URL = "photos_url"
        const val KEY_HOME_ASSISTANT_URL = "home_assistant_url"
        const val KEY_WEATHER_ENTITY_ID = "weather_entity_id"
        const val KEY_HOME_ASSISTANT_FALLBACK_URL = "home_assistant_fallback_url"
    }
}
