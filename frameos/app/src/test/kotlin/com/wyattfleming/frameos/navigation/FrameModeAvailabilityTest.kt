package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameModeAvailabilityTest {
    @Test
    fun `legacy configuration omits Birds from cycling and falls back direct requests to Photos`() {
        val availability = FrameModeAvailability(birdsUrl = null)

        assertEquals(
            listOf(FrameMode.PHOTOS, FrameMode.HOME, FrameMode.WEATHER, FrameMode.OCEAN, FrameMode.CAMERAS, FrameMode.CALENDAR),
            availability.cycleModes,
        )
        assertEquals(FrameMode.PHOTOS, availability.resolve(FrameMode.BIRDS))
        assertFalse(availability.isAvailable(FrameMode.BIRDS))
    }

    @Test
    fun `configured Birds participates in cycling and direct requests`() {
        val availability = FrameModeAvailability("http://birdnet.example.invalid:8080/")

        assertTrue(availability.isAvailable(FrameMode.BIRDS))
        assertEquals(FrameMode.BIRDS, availability.resolve(FrameMode.BIRDS))
        assertEquals(
            listOf(
                FrameMode.PHOTOS,
                FrameMode.HOME,
                FrameMode.WEATHER,
                FrameMode.OCEAN,
                FrameMode.BIRDS,
                FrameMode.CAMERAS,
                FrameMode.CALENDAR,
            ),
            availability.cycleModes,
        )
    }

    @Test
    fun `the living room's household order gets Ocean after Weather because the companion cannot name it yet`() {
        val availability = FrameModeAvailability(
            birdsUrl = null,
            requestedModes = listOf(FrameMode.PHOTOS, FrameMode.HOME, FrameMode.WEATHER, FrameMode.BIRDS, FrameMode.CAMERAS, FrameMode.CALENDAR),
        )

        assertEquals(
            listOf(FrameMode.PHOTOS, FrameMode.HOME, FrameMode.WEATHER, FrameMode.OCEAN, FrameMode.CAMERAS, FrameMode.CALENDAR),
            availability.cycleModes,
        )
        assertEquals(FrameMode.OCEAN, availability.resolve(FrameMode.OCEAN))
    }

    @Test
    fun `requested photo weather order supports parents preset`() {
        val availability = FrameModeAvailability(
            birdsUrl = null,
            requestedModes = listOf(FrameMode.PHOTOS, FrameMode.WEATHER),
        )

        assertEquals("a hand-picked list never gains Ocean", listOf(FrameMode.PHOTOS, FrameMode.WEATHER), availability.cycleModes)
        assertEquals(FrameMode.PHOTOS, availability.resolve(FrameMode.HOME))
        assertEquals(FrameMode.PHOTOS, availability.resolve(FrameMode.OCEAN))
    }
}
