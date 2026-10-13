package com.wyattfleming.touchframe.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TouchProvisionContractTest {
    @Test
    fun `valid bounded non secret inputs create an initial configuration`() {
        val configuration = TouchProvisionContract.parse(
            photosUrl = "https://photos.example/frame",
            homeAssistantUrl = "https://ha.example:8443/dashboard",
            weatherEntityId = "weather.home",
            artUrl = "https://art.example/feed",
            climateEntityId = "climate.entryway",
            calendarEntityIds = listOf("calendar.family", "calendar.work"),
            cameraEntityIds = listOf("camera.backyard"),
        )

        assertNotNull(configuration)
        assertEquals("https://ha.example:8443", configuration?.homeAssistantOrigin)
        assertEquals(listOf("calendar.family", "calendar.work"), configuration?.calendarEntityIds)
        assertEquals(listOf("camera.backyard"), configuration?.cameraEntityIds)
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
