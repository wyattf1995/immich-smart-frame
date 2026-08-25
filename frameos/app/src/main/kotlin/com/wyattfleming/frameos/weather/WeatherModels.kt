package com.wyattfleming.frameos.weather

enum class WeatherForecastType(val apiValue: String) {
    DAILY("daily"),
    HOURLY("hourly"),
}

enum class WeatherCondition(val displayName: String) {
    CLEAR_NIGHT("Clear night"),
    CLOUDY("Cloudy"),
    EXCEPTIONAL("Unusual weather"),
    FOG("Foggy"),
    HAIL("Hail"),
    LIGHTNING("Thunderstorms"),
    LIGHTNING_RAINY("Rain and thunderstorms"),
    PARTLY_CLOUDY("Partly cloudy"),
    POURING("Heavy rain"),
    RAINY("Rain"),
    SNOWY("Snow"),
    SNOWY_RAINY("Wintry mix"),
    SUNNY("Sunny"),
    WINDY("Windy"),
    WINDY_VARIANT("Windy and cloudy"),
    UNKNOWN("Weather unavailable"),
    ;

    companion object {
        fun fromHomeAssistant(value: String): WeatherCondition = when (value) {
            "clear-night" -> CLEAR_NIGHT
            "cloudy" -> CLOUDY
            "exceptional" -> EXCEPTIONAL
            "fog" -> FOG
            "hail" -> HAIL
            "lightning" -> LIGHTNING
            "lightning-rainy" -> LIGHTNING_RAINY
            "partlycloudy" -> PARTLY_CLOUDY
            "pouring" -> POURING
            "rainy" -> RAINY
            "snowy" -> SNOWY
            "snowy-rainy" -> SNOWY_RAINY
            "sunny" -> SUNNY
            "windy" -> WINDY
            "windy-variant" -> WINDY_VARIANT
            else -> UNKNOWN
        }
    }
}

data class WeatherCurrent(
    val entityId: String,
    val condition: WeatherCondition,
    val displayCondition: String,
    val temperature: Double,
    val temperatureUnit: String,
    val humidity: Double?,
    val windSpeed: Double?,
    val windSpeedUnit: String?,
    val windBearing: Double?,
    val pressure: Double?,
    val pressureUnit: String? = null,
    val visibility: Double?,
    val visibilityUnit: String?,
    val updatedAtEpochMillis: Long,
    val apparentTemperature: Double? = null,
    val dewPoint: Double? = null,
    val cloudCoverage: Double? = null,
    val uvIndex: Double? = null,
)

data class WeatherForecast(
    val type: WeatherForecastType,
    val dateTime: String,
    val condition: WeatherCondition,
    val displayCondition: String,
    val temperature: Double,
    val templow: Double?,
    val precipitationProbability: Int?,
    val apparentTemperature: Double? = null,
    val humidity: Double? = null,
    val windSpeed: Double? = null,
    val windBearing: Double? = null,
)

data class WeatherSnapshot(
    val current: WeatherCurrent,
    val daily: List<WeatherForecast>,
    val hourly: List<WeatherForecast>,
    val alert: String?,
)

data class CachedWeatherSnapshot(
    val snapshot: WeatherSnapshot,
    val savedAtEpochMillis: Long,
)

sealed interface WeatherRemoteResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherRemoteResult
    data class Offline(val reason: String) : WeatherRemoteResult
    data class AuthRequired(val reason: String) : WeatherRemoteResult
}

interface WeatherRemote {
    fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult
    fun cancel() = Unit
}

interface WeatherCache {
    fun read(entityId: String): CachedWeatherSnapshot?
    fun write(entityId: String, snapshot: WeatherSnapshot, savedAtEpochMillis: Long)
    fun recordError(message: String)
}

sealed interface WeatherLoadResult {
    data class Fresh(val snapshot: WeatherSnapshot) : WeatherLoadResult
    data class Stale(
        val snapshot: WeatherSnapshot,
        val authenticationRequired: Boolean = false,
    ) : WeatherLoadResult
    data object Offline : WeatherLoadResult
    data object AuthRequired : WeatherLoadResult
}
