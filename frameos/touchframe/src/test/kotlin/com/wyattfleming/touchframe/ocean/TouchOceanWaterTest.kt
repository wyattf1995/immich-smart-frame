package com.wyattfleming.touchframe.ocean

import com.wyattfleming.touchframe.ui.TouchPalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stat labels (Waves, Wind, Sunset) sit on the top of the water. Measured 10/15 on both Touch frames, the muted
 * labels reached only 3.6:1 there with the top stop at #3C8F98, after the shafts were already made fainter.
 */
class TouchOceanWaterTest {
    /** The brightest pixel of a light shaft: white at 0x14 over the water (literal pinned by TouchOceanSceneSourceTest). */
    private fun underShaft(color: Int) = TouchOceanSky.mix(color, 0xFFFFFFFF.toInt(), 0x14 / 255.0)

    @Test
    fun `daytime labels hold four and a half to one on the lightest water, light shafts included`() {
        val lightest = underShaft(TouchOceanWater.DAY.first())
        for ((name, ink) in listOf("ink" to TouchOceanWater.DAY_INK, "muted" to TouchOceanWater.DAY_MUTED)) {
            val ratio = TouchPalette.contrastRatio(ink, lightest)
            assertTrue("$name over the top of the water is $ratio:1", ratio >= 4.5)
        }
    }

    @Test
    fun `the water still darkens with depth by day and night`() {
        for (stops in listOf(TouchOceanWater.DAY, TouchOceanWater.NIGHT)) {
            val luminance = stops.map(TouchOceanSky::luminance)
            assertTrue("water stops must darken downwards: $luminance", luminance.zipWithNext().all { (upper, lower) -> upper > lower })
        }
    }
}
