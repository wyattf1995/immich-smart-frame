package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherRenderCadenceTest {
    @Test
    fun `animated scenes retain smooth frame cadence`() {
        assertEquals(
            50L,
            WeatherRenderCadence.nextDelayMillis(
                sceneAnimated = true,
                hourlyItemCount = 24,
                pageElapsedMillis = 4_250L,
            ),
        )
    }

    @Test
    fun `static scenes sleep until the hourly page boundary`() {
        assertEquals(
            10_000L,
            WeatherRenderCadence.nextDelayMillis(
                sceneAnimated = false,
                hourlyItemCount = 24,
                pageElapsedMillis = 0L,
            ),
        )
        assertEquals(
            1L,
            WeatherRenderCadence.nextDelayMillis(
                sceneAnimated = false,
                hourlyItemCount = 24,
                pageElapsedMillis = 9_999L,
            ),
        )
    }

    @Test
    fun `static one page forecasts need no scheduled redraw`() {
        assertNull(
            WeatherRenderCadence.nextDelayMillis(
                sceneAnimated = false,
                hourlyItemCount = 12,
                pageElapsedMillis = 5_000L,
            ),
        )
    }
}
