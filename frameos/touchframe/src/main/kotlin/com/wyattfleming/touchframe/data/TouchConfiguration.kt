package com.wyattfleming.touchframe.data

import android.content.Context
import com.wyattfleming.frameos.security.FrameUrlPolicy
import java.net.URI

data class TouchConfiguration(
    val photosUrl: String,
    val homeAssistantUrl: String,
    val homeAssistantOrigin: String,
    val weatherEntityId: String,
    val artUrl: String?,
    val climateEntityId: String,
    val calendarEntityIds: List<String>,
    val cameraEntityIds: List<String>,
) {
    companion object {
        const val DEFAULT_CLIMATE_ENTITY_ID = "climate.entryway_entryway"
        val DEFAULT_CALENDAR_ENTITY_IDS = listOf("calendar.family")
        val DEFAULT_CAMERA_ENTITY_IDS = listOf(
            "camera.backyard_backyard_camera",
            "camera.dining_room_dining_room_camera",
            "camera.garage_garage_camera",
            "camera.living_room_living_room_camera",
        )

        fun from(
            photosUrl: String,
            homeAssistantUrl: String,
            weatherEntityId: String,
            artUrl: String?,
            climateEntityId: String = DEFAULT_CLIMATE_ENTITY_ID,
            calendarEntityIds: List<String> = DEFAULT_CALENDAR_ENTITY_IDS,
            cameraEntityIds: List<String> = DEFAULT_CAMERA_ENTITY_IDS,
            urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
        ): TouchConfiguration? {
            val photos = photosUrl.trim()
            val home = homeAssistantUrl.trim()
            val weather = weatherEntityId.trim()
            val art = artUrl?.trim()?.ifEmpty { null }
            val climate = climateEntityId.trim()
            if (!urlPolicy.isSafeConfigurationUrl(photos) || !urlPolicy.isSafeConfigurationUrl(home)) return null
            if (art != null && !urlPolicy.isSafeConfigurationUrl(art)) return null
            val homeUri = runCatching { URI(home) }.getOrNull() ?: return null
            if (!homeUri.scheme.equals("https", ignoreCase = true) || homeUri.host.isNullOrBlank() || homeUri.userInfo != null) return null
            if (!WEATHER_ENTITY.matches(weather) || !CLIMATE_ENTITY.matches(climate)) return null
            val calendars = normalize(calendarEntityIds, CALENDAR_ENTITY, MAX_CALENDARS)
            val cameras = normalize(cameraEntityIds, CAMERA_ENTITY, MAX_CAMERAS)
            return TouchConfiguration(
                photosUrl = photos,
                homeAssistantUrl = home,
                homeAssistantOrigin = origin(homeUri),
                weatherEntityId = weather,
                artUrl = art,
                climateEntityId = climate,
                calendarEntityIds = calendars.ifEmpty { DEFAULT_CALENDAR_ENTITY_IDS },
                cameraEntityIds = cameras.ifEmpty { DEFAULT_CAMERA_ENTITY_IDS },
            )
        }

        private fun normalize(values: List<String>, pattern: Regex, max: Int): List<String> = values
            .asSequence()
            .map(String::trim)
            .filter(pattern::matches)
            .distinct()
            .take(max)
            .toList()

        private fun origin(uri: URI): String = buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(uri.host.lowercase())
            val port = uri.port
            if (port >= 0 && !(uri.scheme.equals("https", true) && port == 443)) append(":$port")
        }

        private const val MAX_CALENDARS = 4
        private const val MAX_CAMERAS = 4
        private val WEATHER_ENTITY = Regex("weather\\.[a-z0-9_]+")
        private val CLIMATE_ENTITY = Regex("climate\\.[a-z0-9_]+")
        private val CALENDAR_ENTITY = Regex("calendar\\.[a-z0-9_]+")
        private val CAMERA_ENTITY = Regex("camera\\.[a-z0-9_]+")
    }
}

/**
 * Reads TouchFrame's copied, non-secret configuration. Android isolates SharedPreferences per
 * package; this deliberately cannot read FrameOS OAuth data or another package's private prefs.
 */
class TouchConfigurationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): TouchConfiguration? {
        val photos = preferences.getString(KEY_PHOTOS_URL, null) ?: return null
        val home = preferences.getString(KEY_HOME_ASSISTANT_URL, null) ?: return null
        val weather = preferences.getString(KEY_WEATHER_ENTITY_ID, null) ?: return null
        return TouchConfiguration.from(
            photosUrl = photos,
            homeAssistantUrl = home,
            weatherEntityId = weather,
            artUrl = preferences.getString(KEY_ART_URL, null),
            climateEntityId = preferences.getString(KEY_CLIMATE_ENTITY_ID, TouchConfiguration.DEFAULT_CLIMATE_ENTITY_ID)
                ?: TouchConfiguration.DEFAULT_CLIMATE_ENTITY_ID,
            calendarEntityIds = preferences.getStringSet(KEY_CALENDAR_ENTITY_IDS, null)?.toList()
                ?: TouchConfiguration.DEFAULT_CALENDAR_ENTITY_IDS,
            cameraEntityIds = preferences.getStringSet(KEY_CAMERA_ENTITY_IDS, null)?.toList()
                ?: TouchConfiguration.DEFAULT_CAMERA_ENTITY_IDS,
        )
    }

    /**
     * Synchronously commits the first valid configuration. Call only from a worker: `commit()`
     * is deliberately used so a successful provisioning result survives process death.
     */
    fun writeInitialDurably(configuration: TouchConfiguration): InitialWriteResult = synchronized(preferences) {
        if (hasInitialConfiguration()) return InitialWriteResult.ALREADY_PRESENT
        val committed = preferences.edit()
            .putString(KEY_PHOTOS_URL, configuration.photosUrl)
            .putString(KEY_HOME_ASSISTANT_URL, configuration.homeAssistantUrl)
            .putString(KEY_WEATHER_ENTITY_ID, configuration.weatherEntityId)
            .putString(KEY_ART_URL, configuration.artUrl)
            .putString(KEY_CLIMATE_ENTITY_ID, configuration.climateEntityId)
            .putStringSet(KEY_CALENDAR_ENTITY_IDS, configuration.calendarEntityIds.toSet())
            .putStringSet(KEY_CAMERA_ENTITY_IDS, configuration.cameraEntityIds.toSet())
            .commit()
        if (committed) InitialWriteResult.WRITTEN else InitialWriteResult.FAILED
    }

    private fun hasInitialConfiguration(): Boolean = INITIAL_KEYS.any(preferences::contains)

    enum class InitialWriteResult { WRITTEN, ALREADY_PRESENT, FAILED }

    private companion object {
        const val PREFERENCES_NAME = "frameos_configuration"
        const val KEY_PHOTOS_URL = "photos_url"
        const val KEY_HOME_ASSISTANT_URL = "home_assistant_url"
        const val KEY_WEATHER_ENTITY_ID = "weather_entity_id"
        const val KEY_ART_URL = "art_url"
        const val KEY_CLIMATE_ENTITY_ID = "climate_entity_id"
        const val KEY_CALENDAR_ENTITY_IDS = "calendar_entity_ids"
        const val KEY_CAMERA_ENTITY_IDS = "camera_entity_ids"
        val INITIAL_KEYS = setOf(
            KEY_PHOTOS_URL,
            KEY_HOME_ASSISTANT_URL,
            KEY_WEATHER_ENTITY_ID,
            KEY_ART_URL,
            KEY_CLIMATE_ENTITY_ID,
            KEY_CALENDAR_ENTITY_IDS,
            KEY_CAMERA_ENTITY_IDS,
        )
    }
}
