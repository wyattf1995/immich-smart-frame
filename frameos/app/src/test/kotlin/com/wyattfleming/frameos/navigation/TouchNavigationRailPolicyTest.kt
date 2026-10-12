package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchNavigationRailPolicyTest {
    @Test
    fun `Home is first once and all available modes retain their configured order`() {
        assertEquals(
            listOf(FrameMode.HOME, FrameMode.PHOTOS, FrameMode.ART, FrameMode.WEATHER, FrameMode.CAMERAS, FrameMode.CALENDAR),
            TouchNavigationRailPolicy.ordered(
                listOf(FrameMode.PHOTOS, FrameMode.ART, FrameMode.HOME, FrameMode.WEATHER, FrameMode.CAMERAS, FrameMode.CALENDAR),
            ),
        )
    }

    @Test
    fun `unavailable Home is not fabricated into the rail`() {
        assertEquals(
            listOf(FrameMode.PHOTOS, FrameMode.WEATHER),
            TouchNavigationRailPolicy.ordered(listOf(FrameMode.PHOTOS, FrameMode.WEATHER)),
        )
    }
}
