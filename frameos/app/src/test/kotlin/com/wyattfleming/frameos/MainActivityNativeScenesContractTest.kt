package com.wyattfleming.frameos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Lenovo FrameOS shows the shared Weather page and Ocean (from :frame-ui). Guards what JVM tests cannot run here:
 * which view each native mode shows, that Ocean animates only while it is on screen, and that sunrise and sunset stay
 * optional so a failed sun read never costs the forecast.
 */
class MainActivityNativeScenesContractTest {
    private val source = Files.readString(Path.of("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt"))

    @Test
    fun `the Lenovo app shows the shared Weather page and Ocean, not the old Weather canvas`() {
        assertTrue(source.contains("private lateinit var weatherContent: TouchWeatherView"))
        assertTrue(source.contains("oceanContent = TouchOceanView(this)"))
        assertFalse(source.contains("WeatherContentView"))
        assertFalse("the new page shows all 24 hours, so volume has nothing to page", source.contains("moveHourlyPage"))
    }

    @Test
    fun `Ocean animates only while it is the page on screen`() {
        val render = methodBody("private fun render(next: FrameState, announce: Boolean)")
        val ocean = render.substringAfter("FrameSurfaceTarget.NativeOcean ->")
        assertTrue(ocean.contains("oceanContent.visibility = View.VISIBLE"))
        assertTrue(ocean.contains("oceanContent.setActive(activityResumed)"))
        val weather = render.substringAfter("FrameSurfaceTarget.NativeWeather ->").substringBefore("FrameSurfaceTarget.NativeOcean ->")
        assertTrue("Weather hides Ocean", weather.contains("hideOcean()"))
        assertTrue("every web page hides Ocean", Regex("""weatherContent\.visibility = View\.GONE\s+hideOcean\(\)""").findAll(render).count() == 3)
        assertTrue(methodBody("private fun hideOcean()").contains("oceanContent.setActive(false)"))
        val pause = methodBody("override fun onPause()")
        assertTrue(pause.contains("handler.removeCallbacks(refreshNativeScenes)"))
        assertTrue(pause.contains("handler.removeCallbacks(refreshVisibleOcean)"))
        assertTrue(pause.contains("oceanContent.setActive(false)"))
    }

    @Test
    fun `the forecast is drawn from the load itself and sunrise and sunset never cost it`() {
        val refresh = methodBody("private fun refreshWeather()")
        assertTrue(refresh.contains("coordinator.refreshForDisplay()"))
        assertTrue("the sun read is optional", refresh.contains("runCatching { sunReader?.load(token, WeatherRequestDeadline(timeoutMillis = SUN_TIMEOUT_MILLIS)) }.getOrNull()"))
        assertTrue("only a fresh forecast reads the sun", refresh.contains("takeIf { display.load is WeatherLoadResult.Fresh }"))
        val show = methodBody("private fun showWeatherLoad(load: WeatherLoadResult, sun: TouchSunTimes?)")
        assertTrue("an older sun read is kept when a new one fails", show.contains("sun = sun ?: weatherState.sun"))
    }

    private fun methodBody(signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) when (source[index]) {
            '{' -> depth += 1
            '}' -> if (--depth == 0) return source.substring(open, index + 1)
        }
        error("Unterminated method: $signature")
    }
}
