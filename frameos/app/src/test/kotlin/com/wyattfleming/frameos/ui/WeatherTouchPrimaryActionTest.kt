package com.wyattfleming.frameos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTouchPrimaryActionTest {
    @Test
    fun signInPresentationWithConfiguredActionIsTouchAccessible() {
        assertTrue(
            WeatherTouchPrimaryAction.isAvailable(
                emptyMessage = "Weather needs Home Assistant sign-in",
                actionConfigured = true,
            ),
        )
        assertEquals(
            "Tap to connect securely",
            WeatherTouchPrimaryAction.detailFor(
                emptyMessage = "Weather needs Home Assistant sign-in",
                actionConfigured = true,
            ),
        )
        assertEquals(
            "Weather needs Home Assistant sign-in. Tap to connect securely",
            WeatherTouchPrimaryAction.contentDescriptionFor(
                emptyMessage = "Weather needs Home Assistant sign-in",
                fallbackDescription = "unused",
                actionConfigured = true,
            ),
        )
    }

    @Test
    fun missingActionOrOtherWeatherStateCannotExposeConnectTap() {
        assertFalse(
            WeatherTouchPrimaryAction.isAvailable(
                emptyMessage = "Weather needs Home Assistant sign-in",
                actionConfigured = false,
            ),
        )
        assertFalse(
            WeatherTouchPrimaryAction.isAvailable(
                emptyMessage = "Weather is temporarily unavailable",
                actionConfigured = true,
            ),
        )
        assertEquals(
            "The last forecast will return automatically",
            WeatherTouchPrimaryAction.detailFor(
                emptyMessage = "Weather needs Home Assistant sign-in",
                actionConfigured = false,
            ),
        )
        assertEquals(
            "Weather is temporarily unavailable",
            WeatherTouchPrimaryAction.contentDescriptionFor(
                emptyMessage = "Weather is temporarily unavailable",
                fallbackDescription = "unused",
                actionConfigured = true,
            ),
        )
    }
}
