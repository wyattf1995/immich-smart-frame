package com.wyattfleming.touchframe.control

import com.wyattfleming.touchframe.data.TouchConfiguration

/** Non-secret, one-time bootstrap input accepted by the DUMP-protected receiver. */
internal object TouchProvisionContract {
    const val ACTION_PROVISION = "com.wyattfleming.touchframe.PROVISION"

    const val EXTRA_PHOTOS_URL = "photos_url"
    const val EXTRA_HOME_ASSISTANT_URL = "home_assistant_url"
    const val EXTRA_WEATHER_ENTITY_ID = "weather_entity_id"
    const val EXTRA_WEATHER_LOCATION_LABEL = "weather_location_label"
    const val EXTRA_ART_URL = "art_url"
    const val EXTRA_BIRDS_URL = "birds_url"
    const val EXTRA_CLIMATE_ENTITY_ID = "climate_entity_id"
    const val EXTRA_CALENDAR_ENTITY_IDS = "calendar_entity_ids"
    const val EXTRA_CAMERA_ENTITY_IDS = "camera_entity_ids"

    const val RESULT_OK = -1
    const val RESULT_INVALID = 1
    const val RESULT_FAILED = 2

    private const val MAX_URL_CHARS = 4096
    private const val MAX_ENTITY_CHARS = 128
    private const val MAX_CALENDARS = 4
    private const val MAX_CAMERAS = 4

    fun parse(
        photosUrl: String?,
        homeAssistantUrl: String?,
        weatherEntityId: String?,
        artUrl: String?,
        climateEntityId: String?,
        calendarEntityIds: List<String>?,
        cameraEntityIds: List<String>?,
        birdsUrl: String? = null,
        weatherLocationLabel: String? = null,
    ): TouchConfiguration? {
        if (!requiredUrl(photosUrl) || !requiredUrl(homeAssistantUrl) || !requiredEntity(weatherEntityId)) return null
        if (artUrl != null && artUrl.length > MAX_URL_CHARS) return null
        if (birdsUrl != null && birdsUrl.length > MAX_URL_CHARS) return null
        if (weatherLocationLabel != null && weatherLocationLabel.length > 256) return null
        if (climateEntityId != null && !boundedEntity(climateEntityId)) return null
        if (!boundedEntities(calendarEntityIds, MAX_CALENDARS) || !boundedEntities(cameraEntityIds, MAX_CAMERAS)) return null
        return TouchConfiguration.from(
            photosUrl = photosUrl.orEmpty(),
            homeAssistantUrl = homeAssistantUrl.orEmpty(),
            weatherEntityId = weatherEntityId.orEmpty(),
            artUrl = artUrl,
            birdsUrl = birdsUrl,
            weatherLocationLabel = weatherLocationLabel,
            climateEntityId = climateEntityId ?: TouchConfiguration.DEFAULT_CLIMATE_ENTITY_ID,
            calendarEntityIds = calendarEntityIds ?: TouchConfiguration.DEFAULT_CALENDAR_ENTITY_IDS,
            cameraEntityIds = cameraEntityIds ?: TouchConfiguration.DEFAULT_CAMERA_ENTITY_IDS,
        )
    }

    private fun requiredUrl(value: String?): Boolean = value != null && value.length <= MAX_URL_CHARS && value.isNotBlank()

    private fun requiredEntity(value: String?): Boolean = value != null && boundedEntity(value)

    private fun boundedEntity(value: String): Boolean = value.length <= MAX_ENTITY_CHARS

    private fun boundedEntities(values: List<String>?, max: Int): Boolean =
        values == null || values.size <= max && values.all(::boundedEntity)
}
