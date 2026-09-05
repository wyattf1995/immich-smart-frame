package com.wyattfleming.frameos.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class FrameConfigurationPatchTest {
    private val existing = FrameConfiguration(
        photosUrl = "https://photos.example.invalid/",
        homeAssistantUrl = "https://home.example.invalid/",
        weatherEntityId = "weather.back_yard",
        homeAssistantFallbackUrl = "https://home-fallback.example.invalid/",
    )

    @Test
    fun `Birds-only upgrade preserves every existing setting`() {
        val updated = FrameConfigurationPatch.withBirdsUrl(
            existing = existing,
            birdsUrl = "  http://birdnet.example.invalid:8080/ui/dashboard  ",
        )

        assertNotNull(updated)
        assertEquals(existing.photosUrl, updated?.photosUrl)
        assertEquals(existing.homeAssistantUrl, updated?.homeAssistantUrl)
        assertEquals(existing.weatherEntityId, updated?.weatherEntityId)
        assertEquals(existing.homeAssistantFallbackUrl, updated?.homeAssistantFallbackUrl)
        assertEquals("http://birdnet.example.invalid:8080/ui/dashboard", updated?.birdsUrl)
    }

    @Test
    fun `blank Birds URL disables the optional mode without changing other settings`() {
        val configured = existing.copy(birdsUrl = "http://birdnet.example.invalid:8080/ui/dashboard")

        val updated = FrameConfigurationPatch.withBirdsUrl(configured, "  ")

        assertEquals(existing, updated)
    }

    @Test
    fun `unsafe Birds URL rejects the patch and preserves the stored configuration`() {
        assertNull(
            FrameConfigurationPatch.withBirdsUrl(
                existing = existing,
                birdsUrl = "https://user:password@birdnet.example.invalid/",
            ),
        )
    }
}
