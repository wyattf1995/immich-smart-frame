package com.wyattfleming.touchframe.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards what JVM tests cannot see on the Weather page: every animated frame costs these frames 14–21 ms, so the page
 * stays still and draws without allocating, a text added to a column without its own layout params gets clipped, and
 * Touch shows this page rather than the shared Lenovo canvas.
 */
class TouchWeatherViewSourceTest {
    private val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/weather/TouchWeatherView.kt").readText()
    private val activity = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt").readText()

    /** The bodies of every `onDraw`, found by matching braces. */
    private fun drawBodies(): List<String> = Regex("""override fun onDraw\(canvas: Canvas\) \{""").findAll(source).map { match ->
        var depth = 1
        var index = match.range.last + 1
        while (depth > 0) {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        }
        source.substring(match.range.last + 1, index - 1)
    }.toList()

    @Test
    fun `the page never animates and moves with the clock only every five minutes`() {
        listOf("ValueAnimator", "ObjectAnimator", "postInvalidateOnAnimation", "Choreographer", ".animate()").forEach {
            assertFalse("Weather must not animate ($it)", source.contains(it))
        }
        assertTrue(source.contains("const val SLOT_MILLIS = 5 * 60_000L"))
    }

    @Test
    fun `drawing allocates nothing, labels and paths are built when the data or size changes`() {
        val bodies = drawBodies()
        assertEquals("the glyph beside the temperature, the 24-hour ridge and the week", 3, bodies.size)
        bodies.forEach { body ->
            listOf("Path", "RectF", "Paint", "ArrayList", "listOf", "floatArrayOf", "LinearGradient").forEach {
                assertFalse("onDraw allocates $it", Regex("""\b$it\(""").containsMatchIn(body))
            }
            assertFalse("onDraw builds a string", Regex("\"[^\"\\n]*\\$").containsMatchIn(body))
        }
    }

    @Test
    fun `every text added to a column declares its own layout params`() {
        var from = source.indexOf("addView(kit.text(")
        var count = 0
        while (from >= 0) {
            var index = from + "addView(".length
            var depth = 1
            var arguments = 1
            while (depth > 0) {
                when (source[index]) {
                    '"' -> { index++; while (source[index] != '"') { if (source[index] == '\\') index++; index++ } }
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> depth--
                    ',' -> if (depth == 1) arguments++
                }
                index++
            }
            assertEquals("text added without layout params near: ${source.substring(from, minOf(from + 80, source.length))}", 2, arguments)
            count++
            from = source.indexOf("addView(kit.text(", index)
        }
        assertTrue("expected the sky band's and empty state's texts, found $count", count >= 8)
    }

    @Test
    fun `Touch shows its own weather page, not the shared Lenovo canvas`() {
        assertTrue(activity.contains("weather = TouchWeatherView(this, ::refreshWeather, ::signIn)"))
        assertFalse(activity.contains("WeatherContentView"))
        assertTrue("the forecast is reread every ten minutes", activity.contains("const val WEATHER_REFRESH_MILLIS = 10 * 60_000L"))
    }
}
