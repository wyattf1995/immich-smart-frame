package com.wyattfleming.touchframe.weather

import com.wyattfleming.touchframe.ui.TouchPalette
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The Weather page's sky, field and ground; text uses Touch's day and night inks. */
data class TouchWeatherColors(
    val skyTop: Int,
    val skyBottom: Int,
    val field: Int,
    val ground: Int,
    val ink: Int,
    val muted: Int,
    val rule: Int,
    /** Translucent wash behind the night hours of the curve. */
    val nightBand: Int,
    val rain: Int,
    val icon: Int,
    /** The sun's disc: filled so a clear day reads from across the room. */
    val sun: Int,
    /** Translucent panel behind today in the week. */
    val todayPanel: Int,
) {
    companion object {
        /** A pale sky over a cool field by day. */
        val DAY = TouchWeatherColors(
            skyTop = 0xFFB9D2E3.toInt(), skyBottom = 0xFFDCE8EF.toInt(), field = 0xFFE7EDF0.toInt(), ground = 0xFFDCE3E5.toInt(),
            ink = 0xFF15212B.toInt(), muted = 0xFF44525D.toInt(), rule = 0xFFB7C4CC.toInt(), nightBand = 0x1A273E54,
            rain = 0xFF1D5487.toInt(), icon = 0xFF15212B.toInt(), sun = 0xFFC98A12.toInt(), todayPanel = 0x8CFFFFFF.toInt(),
        )

        /** Touch's bedside night: black field, everything in the night amber. */
        val NIGHT = TouchWeatherColors(
            skyTop = 0xFF0A0907.toInt(), skyBottom = 0xFF0A0907.toInt(), field = 0xFF0A0907.toInt(), ground = 0xFF0F0D0A.toInt(),
            ink = TouchPalette.NIGHT.ink, muted = TouchPalette.NIGHT.inkMuted, rule = 0xFF2A241B.toInt(), nightBand = 0x0DB39668,
            rain = TouchPalette.NIGHT.ink, icon = TouchPalette.NIGHT.ink, sun = TouchPalette.NIGHT.ink, todayPanel = 0x0FB39668,
        )

        fun forNight(night: Boolean) = if (night) NIGHT else DAY

        /** °F stops from cold blue through green and amber to hot red; the curve's height says the same, so colour is never the only cue. */
        private val DAY_RAMP = listOf(
            45.0 to intArrayOf(70, 110, 160), 58.0 to intArrayOf(96, 148, 176), 68.0 to intArrayOf(128, 166, 132),
            78.0 to intArrayOf(214, 168, 72), 88.0 to intArrayOf(206, 110, 52), 98.0 to intArrayOf(160, 52, 34),
        )

        /** Colour for a temperature in °F: by night a dim-to-bright amber instead of hues. */
        fun ramp(fahrenheit: Double, night: Boolean): Int {
            if (night) {
                val a = ((fahrenheit - 50.0) / 50.0).coerceIn(0.0, 1.0)
                return rgb(70 + a * 109, 58 + a * 92, 40 + a * 64)
            }
            if (fahrenheit <= DAY_RAMP.first().first) return DAY_RAMP.first().second.let { rgb(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
            for (i in 1 until DAY_RAMP.size) {
                val (t1, c1) = DAY_RAMP[i]
                if (fahrenheit <= t1) {
                    val (t0, c0) = DAY_RAMP[i - 1]
                    val f = (fahrenheit - t0) / (t1 - t0)
                    return rgb(c0[0] + (c1[0] - c0[0]) * f, c0[1] + (c1[1] - c0[1]) * f, c0[2] + (c1[2] - c0[2]) * f)
                }
            }
            return DAY_RAMP.last().second.let { rgb(it[0].toDouble(), it[1].toDouble(), it[2].toDouble()) }
        }

        fun toFahrenheit(value: Double, fahrenheit: Boolean): Double = if (fahrenheit) value else value * 9.0 / 5.0 + 32.0

        private fun rgb(r: Double, g: Double, b: Double): Int =
            (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
    }
}

/**
 * Monotone cubic (Fritsch–Carlson) through the readings: the line passes through every point and never bulges above
 * a high or below a low, so the drawn curve never shows a temperature the forecast does not.
 */
object TouchWeatherCurve {
    /** Tangent at each point for x-ordered [xs] and [ys]; flat stretches and turning points get zero. */
    fun tangents(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        require(n == ys.size)
        val m = DoubleArray(n)
        if (n < 2) return m
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0.0) 0.0 else (d[i - 1] + d[i]) / 2.0
        for (i in 0 until n - 1) {
            if (d[i] == 0.0) {
                m[i] = 0.0
                m[i + 1] = 0.0
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val r = a * a + b * b
            if (r > 9.0) {
                val k = 3.0 / sqrt(r)
                m[i] = k * a * d[i]
                m[i + 1] = k * b * d[i]
            }
        }
        return m
    }

    /** Bezier control points for segment [i]: (c1x, c1y, c2x, c2y). */
    fun controls(xs: DoubleArray, ys: DoubleArray, m: DoubleArray, i: Int): DoubleArray {
        val h = (xs[i + 1] - xs[i]) / 3.0
        return doubleArrayOf(xs[i] + h, ys[i] + m[i] * h, xs[i + 1] - h, ys[i + 1] - m[i + 1] * h)
    }
}
