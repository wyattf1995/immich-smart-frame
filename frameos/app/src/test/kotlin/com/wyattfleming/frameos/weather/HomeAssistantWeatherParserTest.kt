package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Contract for a dependency-free parser.  The implementation must not use Android APIs so it can
 * be exercised in local JVM tests.
 */
class HomeAssistantWeatherParserTest {
    private val parser = HomeAssistantWeatherParser()

    @Test
    fun `parses a current weather state while retaining optional field absence`() {
        val current = parser.parseCurrentState(
            expectedEntityId = "weather.home",
            payload = """
                {
                  "entity_id":"weather.home",
                  "state":"sunny",
                  "last_updated":"2026-08-24T15:42:00+00:00",
                  "attributes":{
                    "temperature":89,
                    "temperature_unit":"°F",
                    "humidity":22,
                    "wind_speed":8,
                    "wind_speed_unit":"mph",
                    "wind_bearing":315,
                    "visibility":10,
                    "visibility_unit":"mi"
                  }
                }
            """.trimIndent(),
        )

        assertEquals("weather.home", current.entityId)
        assertEquals(WeatherCondition.SUNNY, current.condition)
        assertEquals(89.0, current.temperature, 0.0)
        assertEquals("°F", current.temperatureUnit)
        assertEquals(22.0, requireNotNull(current.humidity), 0.0)
        assertEquals(8.0, requireNotNull(current.windSpeed), 0.0)
        assertEquals("mph", current.windSpeedUnit)
        assertEquals(315.0, requireNotNull(current.windBearing), 0.0)
        assertEquals(10.0, requireNotNull(current.visibility), 0.0)
        assertEquals("mi", current.visibilityUnit)
        assertNull(current.pressure)
    }

    @Test
    fun `parses return_response daily forecasts and keeps optional values nullable`() {
        val forecasts = parser.parseForecastResponse(
            expectedEntityId = "weather.home",
            forecastType = WeatherForecastType.DAILY,
            payload = """
                {
                  "weather.home": {
                    "forecast": [
                      {"datetime":"2026-08-24T00:00:00-07:00","condition":"sunny","temperature":102,"templow":78,"precipitation_probability":0},
                      {"datetime":"2026-08-25T00:00:00-07:00","condition":"partlycloudy","temperature":104,"templow":79}
                    ]
                  }
                }
            """.trimIndent(),
        )

        assertEquals(2, forecasts.size)
        assertEquals(WeatherCondition.SUNNY, forecasts[0].condition)
        assertEquals(102.0, forecasts[0].temperature, 0.0)
        assertEquals(78.0, requireNotNull(forecasts[0].templow), 0.0)
        assertEquals(0, forecasts[0].precipitationProbability)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, forecasts[1].condition)
        assertNull(forecasts[1].precipitationProbability)
    }

    @Test
    fun `parses all 24 hourly entries from return_response without truncating them`() {
        val hours = (0 until 24).joinToString(",") { hour ->
            """{"datetime":"2026-08-24T${hour.toString().padStart(2, '0')}:00:00-07:00","condition":"sunny","temperature":${89 + hour}}"""
        }

        val forecasts = parser.parseForecastResponse(
            expectedEntityId = "weather.home",
            forecastType = WeatherForecastType.HOURLY,
            payload = """{"weather.home":{"forecast":[$hours]}}""",
        )

        assertEquals(24, forecasts.size)
        assertEquals(89.0, forecasts.first().temperature, 0.0)
        assertEquals(112.0, forecasts.last().temperature, 0.0)
    }

    @Test
    fun `rejects malformed payloads and responses for a different entity`() {
        assertThrows(WeatherPayloadException::class.java) {
            parser.parseCurrentState("weather.home", "{not json}")
        }
        assertThrows(WeatherPayloadException::class.java) {
            parser.parseCurrentState(
                "weather.home",
                """{"entity_id":"weather.office","state":"sunny","attributes":{"temperature":89,"temperature_unit":"°F"}}""",
            )
        }
        assertThrows(WeatherPayloadException::class.java) {
            parser.parseForecastResponse(
                expectedEntityId = "weather.home",
                forecastType = WeatherForecastType.DAILY,
                payload = """{"weather.office":{"forecast":[]}}""",
            )
        }
    }

    @Test
    fun `maps an unknown HA condition safely rather than exposing a raw enum`() {
        val current = parser.parseCurrentState(
            "weather.home",
            """{"entity_id":"weather.home","state":"mystery-squall","attributes":{"temperature":89,"temperature_unit":"°F"}}""",
        )

        assertEquals(WeatherCondition.UNKNOWN, current.condition)
        assertFalse(current.displayCondition.contains("mystery-squall"))
    }
}
