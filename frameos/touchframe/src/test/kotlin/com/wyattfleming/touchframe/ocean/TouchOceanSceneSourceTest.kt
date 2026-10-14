package com.wyattfleming.touchframe.ocean

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the scene's cost on a 1 GB wall frame: the runtime audit measured the Weather canvas redrawing itself at
 * 15 fps with 97% janky frames. The ocean scene must only move cached drawings, at a bounded rate, while visible.
 */
class TouchOceanSceneSourceTest {
    private val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ocean/TouchOceanScene.kt").readText()

    private fun body(signature: String, until: String): String {
        val from = source.indexOf(signature)
        assertTrue("missing $signature", from >= 0)
        return source.substring(from, source.indexOf(until, from + signature.length))
    }

    @Test
    fun `each frame only moves views and never redraws the swell, sky or fish`() {
        val perFrame = body("private fun place(", "private fun size(")
        listOf("setWave(", "invalidate(", "Path(", "LinearGradient(", "GradientDrawable(", "setImageResource(", "requestLayout(", "layoutParams =").forEach {
            assertFalse("per-frame code must not call $it", perFrame.contains(it))
        }
        assertTrue(perFrame.contains("translationX ="))
    }

    @Test
    fun `scene updates are capped and stop whenever the scene is hidden or motion is off`() {
        assertTrue(Regex("const val FRAME_MILLIS = (\\d+)L").find(source)!!.groupValues[1].toLong() >= 50L)
        val clock = body("private fun updateClock()", "private fun animationsEnabled()")
        listOf("active", "isShown", "animationsEnabled()").forEach { assertTrue("clock must require $it", clock.contains(it)) }
        assertTrue(source.contains("Settings.Global.ANIMATOR_DURATION_SCALE"))
        assertTrue(source.contains("override fun onDetachedFromWindow()"))
    }

    @Test
    fun `the swell bands are cached textures`() {
        assertTrue(source.contains("listOf(back, middle, front).forEach { it.setLayerType(LAYER_TYPE_HARDWARE, null) }"))
    }
}
