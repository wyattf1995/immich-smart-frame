package com.wyattfleming.touchframe.weather

import com.wyattfleming.touchframe.ui.TouchPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchWeatherColorsTest {
    @Test
    fun `weather text keeps four and a half to one on the sky, field and ground by day and night`() {
        for (colors in listOf(TouchWeatherColors.DAY, TouchWeatherColors.NIGHT)) {
            for (background in listOf(colors.skyTop, colors.skyBottom, colors.field, colors.ground)) {
                for ((name, ink) in listOf("ink" to colors.ink, "muted" to colors.muted, "rain" to colors.rain)) {
                    val ratio = TouchPalette.contrastRatio(ink, background)
                    assertTrue("$name on ${Integer.toHexString(background)} is $ratio:1", ratio >= 4.5)
                }
            }
        }
    }

    @Test
    fun `temperature colour runs cold blue to hot red by day and dim to bright amber by night`() {
        fun red(color: Int) = (color shr 16) and 0xFF
        fun blue(color: Int) = color and 0xFF
        val cold = TouchWeatherColors.ramp(40.0, night = false)
        val hot = TouchWeatherColors.ramp(100.0, night = false)
        assertTrue(blue(cold) > red(cold))
        assertTrue(red(hot) > blue(hot))
        assertEquals(TouchWeatherColors.ramp(45.0, night = false), cold)
        assertEquals(TouchWeatherColors.ramp(98.0, night = false), hot)
        assertEquals(0xFFD6A848.toInt(), TouchWeatherColors.ramp(78.0, night = false))

        val dim = TouchWeatherColors.ramp(50.0, night = true)
        val bright = TouchWeatherColors.ramp(100.0, night = true)
        assertTrue(TouchPalette.contrastRatio(bright, TouchWeatherColors.NIGHT.field) > TouchPalette.contrastRatio(dim, TouchWeatherColors.NIGHT.field))
        assertEquals(0xFFB39668.toInt(), bright)
        assertEquals(TouchWeatherColors.ramp(20.0, night = false), TouchWeatherColors.ramp(TouchWeatherColors.toFahrenheit(-6.7, fahrenheit = false), night = false))
    }

    @Test
    fun `the curve passes through every reading and never overshoots one`() {
        val xs = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
        val ys = doubleArrayOf(0.0, 1.0, 10.0, 10.5, 10.5, 2.0)
        val m = TouchWeatherCurve.tangents(xs, ys)
        for (i in 0 until xs.size - 1) {
            val c = TouchWeatherCurve.controls(xs, ys, m, i)
            val low = minOf(ys[i], ys[i + 1]) - 1e-9
            val high = maxOf(ys[i], ys[i + 1]) + 1e-9
            for (step in 0..100) {
                val t = step / 100.0
                val u = 1 - t
                val y = u * u * u * ys[i] + 3 * u * u * t * c[1] + 3 * u * t * t * c[3] + t * t * t * ys[i + 1]
                assertTrue("segment $i at t=$t is $y, outside $low..$high", y in low..high)
            }
        }
        assertEquals("a flat stretch stays flat", 0.0, m[3], 0.0)
    }
}
