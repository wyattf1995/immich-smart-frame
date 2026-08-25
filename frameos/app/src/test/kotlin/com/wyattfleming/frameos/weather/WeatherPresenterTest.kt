package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class WeatherPresenterTest {
    private val presenter = WeatherPresenter(
        zoneId = ZoneId.of("America/Los_Angeles"),
        locale = Locale.US,
    )

    @Test
    fun `keeps the complete hourly timeline and formats Home Assistant units`() {
        val snapshot = weatherSnapshot(
            hourly = (0 until 24).map { hour ->
                WeatherForecast(
                    type = WeatherForecastType.HOURLY,
                    dateTime = "2026-08-25T${hour.toString().padStart(2, '0')}:00:00-07:00",
                    condition = WeatherCondition.SUNNY,
                    displayCondition = "Sunny",
                    temperature = 70.0 + hour,
                    templow = null,
                    precipitationProbability = if (hour == 4) 30 else 0,
                )
            },
        )

        val presentation = presenter.present(WeatherLoadResult.Fresh(snapshot))

        assertEquals("79°F", presentation.temperature)
        assertEquals("Clear night", presentation.condition)
        assertEquals(WeatherCondition.CLEAR_NIGHT, presentation.sceneCondition)
        assertEquals("67%", presentation.metrics.single { it.label == "Humidity" }.value)
        assertEquals("3.1 mph", presentation.metrics.single { it.label == "Wind" }.value)
        assertEquals(24, presentation.hourly.size)
        assertEquals("Midnight", presentation.hourly.first().time)
        assertEquals("11 PM", presentation.hourly.last().time)
        assertEquals("30%", presentation.hourly[4].precipitation)
        assertEquals(7, presentation.daily.size)
        assertTrue(presentation.status.startsWith("Updated "))
    }

    @Test
    fun `turns repository fallback states into calm actionable screen messages`() {
        val stale = presenter.present(WeatherLoadResult.Stale(weatherSnapshot()))

        assertTrue(stale.status.startsWith("Offline · updated "))
        assertEquals("Weather needs Home Assistant sign-in", presenter.present(WeatherLoadResult.AuthRequired).emptyMessage)
        assertEquals("Weather is temporarily unavailable", presenter.present(WeatherLoadResult.Offline).emptyMessage)
    }

    private fun weatherSnapshot(hourly: List<WeatherForecast> = emptyList()): WeatherSnapshot = WeatherSnapshot(
        current = WeatherCurrent(
            entityId = "weather.home",
            condition = WeatherCondition.CLEAR_NIGHT,
            displayCondition = "Clear night",
            temperature = 79.0,
            temperatureUnit = "°F",
            humidity = 67.0,
            windSpeed = 3.11,
            windSpeedUnit = "mph",
            windBearing = 209.0,
            pressure = 29.93,
            pressureUnit = "inHg",
            visibility = 10.0,
            visibilityUnit = "mi",
            updatedAtEpochMillis = 1_777_173_720_000L,
        ),
        daily = (0 until 7).map { day ->
            WeatherForecast(
                type = WeatherForecastType.DAILY,
                dateTime = "2026-08-${(25 + day).toString().padStart(2, '0')}T00:00:00-07:00",
                condition = WeatherCondition.SUNNY,
                displayCondition = "Sunny",
                temperature = 94.0 + day,
                templow = 72.0 + day,
                precipitationProbability = 0,
            )
        },
        hourly = hourly,
        alert = null,
    )
}
