package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSurfaceRouterTest {
    private val router = FrameSurfaceRouter(
        FrameConfiguration.from(
            photosUrl = "http://frame-host.example.invalid:3000/?curation_profile=family",
            homeAssistantUrl = "https://home-assistant.example.invalid/",
            weatherEntityId = "weather.forecast_home",
        )!!,
    )

    @Test
    fun `photos use a dedicated resumable web surface`() {
        assertEquals(
            FrameSurfaceTarget.Web(
                slot = FrameWebSlot.PHOTOS,
                url = "http://frame-host.example.invalid:3000/?curation_profile=family",
            ),
            router.target(FrameMode.PHOTOS),
        )
    }

    @Test
    fun `Home and Calendar stay warm while Cameras use a disposable surface`() {
        val home = router.target(FrameMode.HOME) as FrameSurfaceTarget.Web
        val cameras = router.target(FrameMode.CAMERAS) as FrameSurfaceTarget.Web
        val calendar = router.target(FrameMode.CALENDAR) as FrameSurfaceTarget.Web

        assertEquals(
            listOf("home", "cameras", "calendar"),
            listOf(home, cameras, calendar).map { it.url.substringAfterLast('#') },
        )
        assertEquals(FrameWebSlot.HOME_ASSISTANT, home.slot)
        assertEquals(FrameWebSlot.CAMERAS, cameras.slot)
        assertEquals(FrameWebSlot.HOME_ASSISTANT, calendar.slot)
        assertEquals(1, listOf(home, cameras, calendar).map { it.url.substringBefore('#') }.distinct().size)

        assertTrue(FrameWebSlot.PHOTOS.isPersistent)
        assertTrue(FrameWebSlot.HOME_ASSISTANT.isPersistent)
        assertFalse(FrameWebSlot.CAMERAS.isPersistent)
        assertFalse(FrameWebSlot.PHOTOS.canPreload)
        assertTrue(FrameWebSlot.HOME_ASSISTANT.canPreload)
        assertFalse(FrameWebSlot.CAMERAS.canPreload)
    }

    @Test
    fun `weather remains native and the hidden HA surface preloads Calendar`() {
        assertEquals(FrameSurfaceTarget.NativeWeather, router.target(FrameMode.WEATHER))
        assertEquals(
            router.target(FrameMode.CALENDAR),
            router.calendarPreloadTarget(),
        )
    }
}
