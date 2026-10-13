package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchIdleReturnPolicyTest {
    private fun returns(
        destination: TouchDestination = TouchDestination.HOME,
        authorizationOpen: Boolean = false,
        configured: Boolean = true,
        networkAllowed: Boolean = true,
        idleMillis: Long = 300_000L,
    ) = TouchIdleReturnPolicy.shouldReturnToPhotos(destination, authorizationOpen, configured, networkAllowed, idleMillis, 120)

    @Test
    fun `idle native screens return to Photos after the configured delay`() {
        assertTrue(returns())
        assertTrue(returns(TouchDestination.CALENDAR))
        assertFalse(returns(idleMillis = 119_999L))
    }

    @Test
    fun `media, sign-in, missing configuration and denied local network never auto-navigate`() {
        assertFalse(returns(TouchDestination.PHOTOS))
        assertFalse(returns(TouchDestination.ART))
        assertFalse(returns(authorizationOpen = true))
        assertFalse(returns(configured = false))
        // Without local-network access every idle period would re-prompt instead of showing Photos.
        assertFalse(returns(networkAllowed = false))
    }
}
