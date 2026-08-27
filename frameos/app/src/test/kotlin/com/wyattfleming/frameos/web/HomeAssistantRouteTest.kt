package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAssistantRouteTest {
    @Test
    fun `builds the versioned same origin wall panel wrapper from a Home Assistant URL`() {
        assertEquals(
            "https://home-assistant.example.invalid:8443/local/frameos-panel.html?v=frameos-1",
            HomeAssistantRoute.wrapperBaseUrl(
                homeAssistantUrl = "https://home-assistant.example.invalid:8443/dashboard/overview?ignored=yes#old",
                version = "frameos-1",
            ),
        )
    }

    @Test
    fun `embedded HA modes use same document fragments so the wrapper stays warm`() {
        val base = "https://home-assistant.example.invalid/local/frameos-panel.html?v=1"

        assertEquals(
            "$base#home",
            HomeAssistantRoute.urlFor(base, FrameMode.HOME),
        )
        assertEquals(
            "$base#cameras",
            HomeAssistantRoute.urlFor(base, FrameMode.CAMERAS),
        )
        assertEquals(
            "$base#calendar",
            HomeAssistantRoute.urlFor(base, FrameMode.CALENDAR),
        )
        assertNull(HomeAssistantRoute.urlFor(base, FrameMode.PHOTOS))
        assertNull(HomeAssistantRoute.urlFor(base, FrameMode.WEATHER))
    }

    @Test
    fun `existing fragments are replaced rather than accumulated`() {
        assertEquals(
            "https://home-assistant.example.invalid/local/frameos-panel.html?v=1#calendar",
            HomeAssistantRoute.urlFor(
                "https://home-assistant.example.invalid/local/frameos-panel.html?v=1#home",
                FrameMode.CALENDAR,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsafe wrapper cache versions`() {
        HomeAssistantRoute.wrapperBaseUrl(
            homeAssistantUrl = "https://home-assistant.example.invalid/",
            version = "bad&view=cameras",
        )
    }
}
