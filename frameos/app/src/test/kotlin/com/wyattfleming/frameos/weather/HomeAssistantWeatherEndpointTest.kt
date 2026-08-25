package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeAssistantWeatherEndpointTest {
    @Test
    fun `derives exact HTTPS state and weather service endpoints from the configured display URL`() {
        val endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl(
            "https://home.example.invalid:8123/local/frameos.html?mode=weather#today",
        )

        assertEquals(
            "https://home.example.invalid:8123/api/states/weather.home",
            endpoint.stateUrl("weather.home"),
        )
        assertEquals(
            "https://home.example.invalid:8123/api/services/weather/get_forecasts",
            endpoint.forecastUrl("weather.home"),
        )
        assertEquals(
            "{\"entity_id\":\"weather.home\",\"type\":\"daily\"}",
            endpoint.forecastRequestBody("weather.home", WeatherForecastType.DAILY),
        )
        assertEquals(
            "{\"entity_id\":\"weather.home\",\"type\":\"hourly\"}",
            endpoint.forecastRequestBody("weather.home", WeatherForecastType.HOURLY),
        )
    }

    @Test
    fun `rejects insecure cross origin and invalid entity inputs`() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantWeatherEndpoint.fromDisplayUrl("http://home.example.invalid:8123/lovelace")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantWeatherEndpoint.fromDisplayUrl("https://photos.example.invalid/frameos")
                .requireSameOrigin("https://home.example.invalid:8123/api/states/weather.home")
        }
        val endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl("https://home.example.invalid:8123/lovelace")
        listOf("", "sensor.outdoor_temperature", "weather.home/../../api/config", "weather.home?token=x").forEach { entityId ->
            assertThrows(IllegalArgumentException::class.java) {
                endpoint.stateUrl(entityId)
            }
        }
    }
}
