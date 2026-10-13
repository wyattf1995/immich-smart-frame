package com.wyattfleming.touchframe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchConfigurationTest {
    @Test
    fun `configuration canonicalizes HTTPS Home Assistant origin and supplies bounded defaults`() {
        val configuration = TouchConfiguration.from(
            photosUrl = "https://photos.example/frame",
            homeAssistantUrl = "https://ha.example:8443/lovelace",
            weatherEntityId = "weather.home",
            artUrl = "https://art.example/feed",
        )!!

        assertEquals("https://ha.example:8443", configuration.homeAssistantOrigin)
        assertEquals("climate.entryway_entryway", configuration.climateEntityId)
        assertEquals(listOf("calendar.family"), configuration.calendarEntityIds)
        assertEquals(4, configuration.cameraEntityIds.size)
    }

    @Test
    fun `configuration rejects insecure Home Assistant and invalid entity domains`() {
        assertNull(TouchConfiguration.from("https://photos.example", "http://ha.example", "weather.home", null))
        assertNull(TouchConfiguration.from("https://photos.example", "https://ha.example?token=no", "weather.home", null))
        assertNull(TouchConfiguration.from("https://photos.example", "https://ha.example", "sensor.outside", null))
        assertNull(TouchConfiguration.from("https://photos.example", "https://ha.example", "weather.home", null, climateEntityId = "light.kitchen"))
    }

    @Test
    fun `entity lists discard invalid values and remain bounded`() {
        val configuration = TouchConfiguration.from(
            "https://photos.example",
            "https://ha.example",
            "weather.home",
            null,
            calendarEntityIds = listOf("calendar.one", "calendar.two", "bad id", "calendar.three", "calendar.four", "calendar.five"),
            cameraEntityIds = listOf("camera.one", "camera.two", "camera.one", "light.bad"),
        )!!
        assertEquals(listOf("calendar.one", "calendar.two", "calendar.three", "calendar.four"), configuration.calendarEntityIds)
        assertEquals(listOf("camera.one", "camera.two"), configuration.cameraEntityIds)
        assertTrue(configuration.calendarEntityIds.all { it.startsWith("calendar.") })
    }


    @Test
    fun `optional Birds feed uses the same safe URL validation as Art`() {
        val configuration = TouchConfiguration.from(
            photosUrl = "https://photos.example",
            homeAssistantUrl = "https://ha.example",
            weatherEntityId = "weather.home",
            artUrl = null,
            birdsUrl = " https://birds.example/feed ",
        )
        assertEquals("https://birds.example/feed", configuration?.birdsUrl)
        assertNull(
            TouchConfiguration.from(
                photosUrl = "https://photos.example",
                homeAssistantUrl = "https://ha.example",
                weatherEntityId = "weather.home",
                artUrl = null,
                birdsUrl = "http://birds.example/feed",
            ),
        )
    }
}
