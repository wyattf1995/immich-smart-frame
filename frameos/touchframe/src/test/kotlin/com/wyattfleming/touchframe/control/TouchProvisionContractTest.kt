package com.wyattfleming.touchframe.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TouchProvisionContractTest {
    @Test
    fun `weather location propagates through bounded initial provisioning`() {
        fun parse(label: String?) = TouchProvisionContract.parse(
            photosUrl = "https://photos.example/frame",
            homeAssistantUrl = "https://ha.example",
            weatherEntityId = "weather.home", artUrl = null,
            climateEntityId = null, calendarEntityIds = null, cameraEntityIds = null,
            weatherLocationLabel = label,
        )
        assertEquals("Portland, OR", parse(" Portland, OR ")?.weatherLocationLabel)
        assertNotNull(parse(null))
        assertNull(parse("x".repeat(257)))
    }

    @Test
    fun `a provisioned frame can change its weather location label and nothing else`() {
        // Provisioning is write-once, so the 1920x1080 frame could never gain the label the 1280x800 frame shows.
        val current = requireNotNull(TouchProvisionContract.parse(
            photosUrl = "https://photos.example/frame",
            homeAssistantUrl = "https://ha.example",
            weatherEntityId = "weather.home", artUrl = null,
            climateEntityId = null, calendarEntityIds = null, cameraEntityIds = null,
        ))
        assertEquals(current.copy(weatherLocationLabel = "Laguna Beach, CA"), TouchProvisionContract.withWeatherLocation(current, " Laguna Beach,\n  CA "))
        val cleared = TouchProvisionContract.withWeatherLocation(current.copy(weatherLocationLabel = "Old place"), "  ")
        assertNotNull(cleared)
        assertNull("a blank label clears it", cleared?.weatherLocationLabel)
        assertNull("over-long input is refused", TouchProvisionContract.withWeatherLocation(current, "x".repeat(257)))
        assertNull("a missing label is refused rather than read as a clear", TouchProvisionContract.withWeatherLocation(current, null))
    }

    @Test
    fun `valid bounded non secret inputs create an initial configuration`() {
        val configuration = TouchProvisionContract.parse(
            photosUrl = "https://photos.example/frame",
            homeAssistantUrl = "https://ha.example:8443/dashboard",
            weatherEntityId = "weather.home",
            artUrl = "https://art.example/feed",
            birdsUrl = "https://birds.example/feed",
            climateEntityId = "climate.entryway",
            calendarEntityIds = listOf("calendar.family", "calendar.work"),
            cameraEntityIds = listOf("camera.backyard"),
        )

        assertNotNull(configuration)
        assertEquals("https://ha.example:8443", configuration?.homeAssistantOrigin)
        assertEquals(listOf("calendar.family", "calendar.work"), configuration?.calendarEntityIds)
        assertEquals(listOf("camera.backyard"), configuration?.cameraEntityIds)
        assertEquals("https://birds.example/feed", configuration?.birdsUrl)
    }

    @Test
    fun `input collection bounds reject oversized provisioning before normalization`() {
        assertNull(
            TouchProvisionContract.parse(
                photosUrl = "https://photos.example/frame",
                homeAssistantUrl = "https://ha.example",
                weatherEntityId = "weather.home",
                artUrl = null,
                climateEntityId = null,
                calendarEntityIds = listOf(
                    "calendar.one",
                    "calendar.two",
                    "calendar.three",
                    "calendar.four",
                    "calendar.five",
                ),
                cameraEntityIds = null,
            ),
        )
        assertNull(
            TouchProvisionContract.parse(
                photosUrl = "https://photos.example/frame",
                homeAssistantUrl = "https://ha.example",
                weatherEntityId = "weather.home",
                artUrl = null,
                climateEntityId = "x".repeat(129),
                calendarEntityIds = null,
                cameraEntityIds = null,
            ),
        )
    }

    @Test
    fun `configuration validation rejects unsafe Home Assistant and invalid entities`() {
        assertNull(
            TouchProvisionContract.parse(
                photosUrl = "https://photos.example/frame",
                homeAssistantUrl = "https://ha.example/?token=not-accepted",
                weatherEntityId = "weather.home",
                artUrl = null,
                climateEntityId = null,
                calendarEntityIds = null,
                cameraEntityIds = null,
            ),
        )
        assertNull(
            TouchProvisionContract.parse(
                photosUrl = "https://photos.example/frame",
                homeAssistantUrl = "https://ha.example",
                weatherEntityId = "sensor.home",
                artUrl = null,
                climateEntityId = null,
                calendarEntityIds = null,
                cameraEntityIds = null,
            ),
        )
    }
}
