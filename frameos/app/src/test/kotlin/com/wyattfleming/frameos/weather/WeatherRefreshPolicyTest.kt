package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherRefreshPolicyTest {
    @Test
    fun `refreshes authenticated weather every five minutes while visible and resumed`() {
        assertEquals(
            5 * 60_000L,
            WeatherRefreshPolicy.nextDelayMillis(
                weatherVisible = true,
                activityResumed = true,
                authenticationRequired = false,
                authorizationInProgress = false,
            ),
        )
    }

    @Test
    fun `does not refresh hidden paused or unauthenticated weather`() {
        assertNull(
            WeatherRefreshPolicy.nextDelayMillis(
                weatherVisible = false,
                activityResumed = true,
                authenticationRequired = false,
                authorizationInProgress = false,
            ),
        )
        assertNull(
            WeatherRefreshPolicy.nextDelayMillis(
                weatherVisible = true,
                activityResumed = false,
                authenticationRequired = false,
                authorizationInProgress = false,
            ),
        )
        assertNull(
            WeatherRefreshPolicy.nextDelayMillis(
                weatherVisible = true,
                activityResumed = true,
                authenticationRequired = true,
                authorizationInProgress = false,
            ),
        )
        assertNull(
            WeatherRefreshPolicy.nextDelayMillis(
                weatherVisible = true,
                activityResumed = true,
                authenticationRequired = false,
                authorizationInProgress = true,
            ),
        )
    }
}
