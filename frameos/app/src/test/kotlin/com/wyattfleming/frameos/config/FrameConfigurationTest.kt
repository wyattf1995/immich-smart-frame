package com.wyattfleming.frameos.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameConfigurationTest {
    @Test
    fun `requires one safe photo URL and one safe Home Assistant URL`() {
        assertNull(
            FrameConfiguration.from(
                photosUrl = "",
                homeAssistantUrl = "https://home-assistant.example.invalid/local/frameos.html",
            ),
        )
        assertNull(
            FrameConfiguration.from(
                photosUrl = "https://photos.example.invalid/?token=secret",
                homeAssistantUrl = "https://home-assistant.example.invalid/local/frameos.html",
            ),
        )
        assertNull(
            FrameConfiguration.from(
                photosUrl = "https://photos.example.invalid/",
                homeAssistantUrl = "javascript:alert(1)",
            ),
        )
        assertNull(
            FrameConfiguration.from(
                photosUrl = "http://frame-host.example.invalid:3000/",
                homeAssistantUrl = "http://home-assistant.example.invalid/",
            ),
        )
    }

    @Test
    fun `normalizes surrounding whitespace but preserves routes and safe queries`() {
        val configuration = FrameConfiguration.from(
            photosUrl = "  http://frame-host.example.invalid:3000/?curation_profile=family  ",
            homeAssistantUrl = "  https://home-assistant.example.invalid/local/frameos.html?v=3#calendar  ",
        )

        assertEquals(
            "http://frame-host.example.invalid:3000/?curation_profile=family",
            configuration?.photosUrl,
        )
        assertEquals(
            "https://home-assistant.example.invalid/local/frameos.html?v=3#calendar",
            configuration?.homeAssistantUrl,
        )
        assertEquals("weather.home", configuration?.weatherEntityId)
    }

    @Test
    fun `accepts only a normalized weather entity ID`() {
        val valid = FrameConfiguration.from(
            photosUrl = "https://photos.example.invalid/",
            homeAssistantUrl = "https://home-assistant.example.invalid/local/frameos.html",
            weatherEntityId = "  weather.forecast_home  ",
        )

        assertEquals("weather.forecast_home", valid?.weatherEntityId)
        listOf("", "sensor.temperature", "weather.home/../../api/config", "weather.home?token=x").forEach { invalid ->
            assertNull(
                FrameConfiguration.from(
                    photosUrl = "https://photos.example.invalid/",
                    homeAssistantUrl = "https://home-assistant.example.invalid/local/frameos.html",
                    weatherEntityId = invalid,
                ),
            )
        }
    }
}
