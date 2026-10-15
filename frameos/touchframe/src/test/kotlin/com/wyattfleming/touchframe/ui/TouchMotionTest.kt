package com.wyattfleming.touchframe.ui

import com.wyattfleming.frameos.weather.WeatherRenderCadence
import com.wyattfleming.touchframe.ocean.TouchOceanScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The motion audit of 2026-10-15: one set of timings for everything that moves, entrances matched by exits, and
 * ambient scenes that hold still when Android's "Remove animations" is on.
 */
class TouchMotionTest {
    private val shell = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt").readText()
    private val weather = File("app/src/main/kotlin/com/wyattfleming/frameos/ui/WeatherContentView.kt").readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        var depth = 0
        var index = source.indexOf('{', start)
        val open = index
        do {
            when (source[index]) {
                '{' -> depth++
                '}' -> depth--
            }
            index++
        } while (depth > 0)
        return source.substring(open, index)
    }

    @Test
    fun `motion tokens give feedback, entrances and fades one timing each, and scenes twenty updates a second`() {
        assertEquals(150L, TouchMotion.QUICK_MILLIS)
        assertEquals(200L, TouchMotion.STANDARD_MILLIS)
        assertEquals(400L, TouchMotion.GENTLE_MILLIS)
        assertEquals(50L, TouchMotion.SCENE_FRAME_MILLIS)
        assertEquals(TouchMotion.SCENE_FRAME_MILLIS, TouchOceanScene.FRAME_MILLIS)
        assertEquals(TouchMotion.SCENE_FRAME_MILLIS, WeatherRenderCadence.ANIMATED_FRAME_DELAY_MILLIS)
    }

    @Test
    fun `with Android animations off an animated weather scene holds still and wakes only for the hourly page`() {
        assertEquals(50L, WeatherRenderCadence.nextDelayMillis(sceneAnimated = true, hourlyItemCount = 24, pageElapsedMillis = 4_250L))
        assertEquals(5_750L, WeatherRenderCadence.nextDelayMillis(sceneAnimated = true, hourlyItemCount = 24, pageElapsedMillis = 4_250L, motionEnabled = false))
        assertNull(WeatherRenderCadence.nextDelayMillis(sceneAnimated = true, hourlyItemCount = 12, pageElapsedMillis = 0L, motionEnabled = false))
    }

    @Test
    fun `the weather view reads Android's animation setting and draws a still scene when it is off`() {
        val draw = body(weather, "override fun onDraw(canvas: Canvas)")
        assertTrue("the scene clock must freeze", draw.contains("if (motionEnabled)"))
        assertTrue("the cadence must know", draw.contains("motionEnabled = motionEnabled"))
        assertFalse("the setting is read on attach and visibility, never per frame", draw.contains("Settings.Global"))
    }

    @Test
    fun `every animation in Touch's shell takes its duration and easing from the motion tokens`() {
        val chains = Regex("""\.animate\(\)[^\n]*""").findAll(shell).map { it.value }.toList()
        assertTrue("expected the menu, hint and notice animations, found ${chains.size}", chains.size >= 5)
        chains.filter { it.contains("setDuration(") }.forEach { chain ->
            assertTrue("literal duration: $chain", Regex("""setDuration\(TouchMotion\.[A-Z_]+\)""").containsMatchIn(chain))
            assertTrue("no easing: $chain", chain.contains("setInterpolator(TouchMotion."))
        }
        assertFalse(shell.contains("MENU_SLIDE_MILLIS"))
        assertFalse(Regex("""setDuration\(\d""").containsMatchIn(shell))
    }

    @Test
    fun `the immersive menu leaves the way it came in`() {
        val hide = body(shell, "fun hideMenu()")
        assertTrue(hide.contains(".translationX(-width.toFloat())"))
        assertTrue(hide.contains("TouchMotion.QUICK_MILLIS") && hide.contains("TouchMotion.exit"))
    }

    @Test
    fun `notices and the edge hint fade in as well as out`() {
        assertTrue(body(shell, "fun showNotice(").contains(".alpha(1f)"))
        assertTrue(body(shell, "fun showEdgeHint()").contains(".alpha(1f)"))
        assertTrue(body(shell, "private fun fadeOutNotice()").contains(".alpha(0f)"))
    }
}
