package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAssistantRouteTest {
    @Test
    fun `HA modes use same document fragments so the wrapper stays warm`() {
        val base = "https://home-assistant.example.invalid/local/frameos.html?v=1"

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
    }

    @Test
    fun `existing fragments are replaced rather than accumulated`() {
        assertEquals(
            "https://home-assistant.example.invalid/local/frameos.html?v=1#calendar",
            HomeAssistantRoute.urlFor(
                "https://home-assistant.example.invalid/local/frameos.html?v=1#home",
                FrameMode.CALENDAR,
            ),
        )
    }
}
