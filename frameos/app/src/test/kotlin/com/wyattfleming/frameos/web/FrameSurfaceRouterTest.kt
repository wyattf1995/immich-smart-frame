package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
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
    fun `Home Assistant views share one versioned warm surface`() {
        val targets = listOf(FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR)
            .map(router::target)
            .map { it as FrameSurfaceTarget.Web }

        assertEquals(
            listOf("home", "cameras", "calendar"),
            targets.map { it.url.substringAfterLast('#') },
        )
        assertTrue(targets.all { it.slot == FrameWebSlot.HOME_ASSISTANT })
        assertEquals(1, targets.map { it.url.substringBefore('#') }.distinct().size)
    }

    @Test
    fun `weather remains a native surface and HA rests on home`() {
        assertEquals(FrameSurfaceTarget.NativeWeather, router.target(FrameMode.WEATHER))
        assertEquals(
            router.target(FrameMode.HOME),
            FrameSurfaceTarget.Web(FrameWebSlot.HOME_ASSISTANT, router.homeAssistantRestingUrl()),
        )
    }
}
