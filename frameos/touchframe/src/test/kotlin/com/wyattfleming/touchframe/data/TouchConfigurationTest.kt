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
    fun `optional Art and Birds feeds preserve HTTP and HTTPS policy and reject unsafe URLs`() {
        val http = TouchConfiguration.from(
            photosUrl = "https://photos.example",
            homeAssistantUrl = "https://ha.example",
            weatherEntityId = "weather.home",
            artUrl = " http://art.example/feed ",
            birdsUrl = " http://birds.example/feed ",
        )
        assertEquals("http://art.example/feed", http?.artUrl)
        assertEquals("http://birds.example/feed", http?.birdsUrl)

        val https = TouchConfiguration.from(
            photosUrl = "https://photos.example",
            homeAssistantUrl = "https://ha.example",
            weatherEntityId = "weather.home",
            artUrl = "https://art.example/feed",
            birdsUrl = "https://birds.example/feed",
        )
        assertEquals("https://art.example/feed", https?.artUrl)
        assertEquals("https://birds.example/feed", https?.birdsUrl)

        assertNull(configurationWith(artUrl = "javascript:alert(1)"))
        assertNull(configurationWith(birdsUrl = "file:///tmp/birds"))
        assertNull(configurationWith(artUrl = "https://user:password@art.example/feed"))
        assertNull(configurationWith(birdsUrl = "https://birds.example/feed?token=not-configuration"))
    }

    private fun configurationWith(artUrl: String? = null, birdsUrl: String? = null): TouchConfiguration? =
        TouchConfiguration.from(
            photosUrl = "https://photos.example",
            homeAssistantUrl = "https://ha.example",
            weatherEntityId = "weather.home",
            artUrl = artUrl,
            birdsUrl = birdsUrl,
        )
}
