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

    @Test
    fun `accepts only a separate safe HTTPS fallback Home Assistant origin`() {
        val configuration = FrameConfiguration.from(
            photosUrl = "https://photos.example.invalid/",
            homeAssistantUrl = "https://home.example.invalid:8123/",
            homeAssistantFallbackUrl = " https://home-fallback.example.invalid:8443/dashboard ",
        )

        assertEquals("https://home-fallback.example.invalid:8443/dashboard", configuration?.homeAssistantFallbackUrl)
        listOf(
            "http://home-fallback.example.invalid/",
            "https://user:password@home-fallback.example.invalid/",
            "https://home-fallback.example.invalid/?token=secret",
        ).forEach { invalid ->
            assertNull(
                FrameConfiguration.from(
                    photosUrl = "https://photos.example.invalid/",
                    homeAssistantUrl = "https://home.example.invalid/",
                    homeAssistantFallbackUrl = invalid,
                ),
            )
        }
    }

    @Test
    fun `Birds URL is optional for upgrades and rejects embedded secrets`() {
        val legacy = FrameConfiguration.from(
            photosUrl = "https://photos.example.invalid/",
            homeAssistantUrl = "https://home.example.invalid/",
        )
        assertNull(legacy?.birdsUrl)

        val configured = FrameConfiguration.from(
            photosUrl = "https://photos.example.invalid/",
            homeAssistantUrl = "https://home.example.invalid/",
            birdsUrl = "http://birdnet.example.invalid:8080/",
        )
        assertEquals("http://birdnet.example.invalid:8080/", configured?.birdsUrl)

        listOf(
            "https://birdnet.example.invalid/?token=secret",
            "https://user:password@birdnet.example.invalid/",
            "javascript:alert(1)",
        ).forEach { invalid ->
            assertNull(
                FrameConfiguration.from(
                    photosUrl = "https://photos.example.invalid/",
                    homeAssistantUrl = "https://home.example.invalid/",
                    birdsUrl = invalid,
                ),
            )
        }
    }
}
