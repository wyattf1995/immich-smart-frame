package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSceneProfileTest {
    @Test
    fun `maps clear sky to daylight glow or night stars and shooting stars`() {
        val sunny = WeatherCondition.SUNNY.sceneProfile()
        val night = WeatherCondition.CLEAR_NIGHT.sceneProfile()

        assertTrue(sunny.sunlight)
        assertFalse(sunny.stars)
        assertTrue(night.stars)
        assertEquals(2, night.shootingStars)
    }

    @Test
    fun `maps precipitation and wind to distinct lightweight motion profiles`() {
        assertEquals(1, WeatherCondition.RAINY.sceneProfile().rainIntensity)
        assertEquals(2, WeatherCondition.POURING.sceneProfile().rainIntensity)
        assertTrue(WeatherCondition.LIGHTNING_RAINY.sceneProfile().lightning)
        assertTrue(WeatherCondition.SNOWY.sceneProfile().snow)
        assertTrue(
            WeatherCondition.WINDY.sceneProfile().cloudSpeedMultiplier >
                WeatherCondition.CLOUDY.sceneProfile().cloudSpeedMultiplier,
        )
    }

    @Test
    fun `keeps fog and unknown conditions calm without invented precipitation`() {
        assertTrue(WeatherCondition.FOG.sceneProfile().fog)
        val unknown = WeatherCondition.UNKNOWN.sceneProfile()
        assertEquals(0, unknown.rainIntensity)
        assertFalse(unknown.snow)
        assertFalse(unknown.lightning)
    }
}
