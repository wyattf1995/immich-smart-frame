package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherSnapshotCodecTest {
    private val codec = WeatherSnapshotCodec()

    @Test
    fun `round trips a cache entry with all 24 hourly forecasts without token output`() {
        val entry = CachedWeatherSnapshot(
            snapshot = WeatherSnapshot(
                current = current(),
                daily = listOf(forecast(WeatherForecastType.DAILY, 0)),
                hourly = (0 until 24).map { forecast(WeatherForecastType.HOURLY, it) },
                alert = "Extreme Heat Watch",
            ),
            savedAtEpochMillis = 1_725_000_000_000L,
        )

        val encoded = codec.encode("weather.home", entry)
        val decoded = codec.decode(expectedEntityId = "weather.home", payload = encoded)

        assertEquals(entry, decoded)
        assertEquals(24, decoded?.snapshot?.hourly?.size)
        assertFalse(encoded.contains("secret-token-value"))
        assertFalse(encoded.contains("Bearer "))
    }

    @Test
    fun `rejects corrupt payload and cache entries whose entity does not match`() {
        assertNull(codec.decode("weather.home", "not a weather cache"))
        assertNull(codec.decode("weather.office", codec.encode("weather.home", CachedWeatherSnapshot(
            WeatherSnapshot(current(), emptyList(), emptyList(), null),
            savedAtEpochMillis = 1L,
        ))))
    }

    private fun current() = WeatherCurrent(
        entityId = "weather.home",
        condition = WeatherCondition.SUNNY,
        displayCondition = "Sunny",
        temperature = 89.0,
        temperatureUnit = "°F",
        humidity = 22.0,
        windSpeed = 8.0,
        windSpeedUnit = "mph",
        windBearing = 315.0,
        pressure = null,
        visibility = null,
        visibilityUnit = null,
        updatedAtEpochMillis = 1_725_000_000_000L,
    )

    private fun forecast(type: WeatherForecastType, hour: Int) = WeatherForecast(
        type = type,
        dateTime = "2026-08-24T${hour.toString().padStart(2, '0')}:00:00-07:00",
        condition = WeatherCondition.SUNNY,
        displayCondition = "Sunny",
        temperature = 89.0 + hour,
        templow = if (type == WeatherForecastType.DAILY) 78.0 else null,
        precipitationProbability = null,
    )
}
