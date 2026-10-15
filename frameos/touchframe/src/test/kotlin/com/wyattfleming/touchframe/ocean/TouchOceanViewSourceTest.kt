package com.wyattfleming.touchframe.ocean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Ocean layout as Android lays it out, which the HTML mockups could not show. Build 10 on the 1920×1080
 * frame clipped "Water temperature" to the width of the line under it, set °F on the numeral's baseline instead of
 * its top, and let a two-line summary run into the tide heading that was pinned to the bottom of the screen.
 */
class TouchOceanViewSourceTest {
    private val source = File("frame-ui/src/main/kotlin/com/wyattfleming/touchframe/ocean/TouchOceanView.kt").readText()

    /** The argument lists of every `addView(kit.text(...) ...)` call, found by matching brackets outside strings. */
    private fun textAdds(): List<List<String>> {
        val calls = mutableListOf<List<String>>()
        var from = source.indexOf("addView(kit.text(")
        while (from >= 0) {
            var index = from + "addView(".length
            var depth = 1
            var start = index
            val arguments = mutableListOf<String>()
            while (depth > 0) {
                when (source[index]) {
                    '"' -> { index++; while (source[index] != '"') { if (source[index] == '\\') index++; index++ } }
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> { depth--; if (depth == 0) arguments += source.substring(start, index).trim() }
                    ',' -> if (depth == 1) { arguments += source.substring(start, index).trim(); start = index + 1 }
                }
                index++
            }
            calls += arguments
            from = source.indexOf("addView(kit.text(", index)
        }
        return calls
    }

    @Test
    fun `every text declares its own layout params so a wrap-content column cannot clip it`() {
        // A child added without params gets MATCH_PARENT in a vertical LinearLayout, and a wrap-content column sizes
        // itself from its other children, then squeezes the full-width one to that size.
        val adds = textAdds()
        assertTrue("expected the overlay's text views, found ${adds.size}", adds.size >= 15)
        adds.forEach { arguments ->
            assertEquals("text added without layout params: ${arguments.first().take(70)}", 2, arguments.size)
        }
    }

    @Test
    fun `the tide section is stacked under the conditions and its chart takes the height that is left`() {
        assertFalse("the tide section must not be anchored to the bottom on its own", Regex("""tideSection\([^)]*\), FrameLayout\.LayoutParams""").containsMatchIn(source))
        assertTrue(Regex("""addView\(tideSection\([^)]*\), LinearLayout\.LayoutParams\(MATCH, 0, 1f\)""").containsMatchIn(source))
        assertTrue("the chart must flex instead of holding a fixed height", Regex("""TouchTideChartView\(context\)[\s\S]*?\}, LinearLayout\.LayoutParams\(MATCH, 0, 1f\)""").containsMatchIn(source))
    }

    @Test
    fun `while the scene moves the text and chart above it are one cached layer released when Ocean is hidden`() {
        // On the 1920x1080 frame every scene update redraws the whole window; without a layer each one re-issues
        // the overlay's ~100 text and chart draws (build 10: 31% RenderThread CPU at 20 updates a second).
        val active = source.substring(source.indexOf("fun setActive("), source.indexOf("/** Called every second"))
        assertTrue(active.contains("overlay.setLayerType(if (value) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, null)"))
        assertTrue(active.contains("scene.setActive(value)"))
    }

    @Test
    fun `a stat's detail may take a second line and the columns keep a gap, so a long swell never runs into Wind`() {
        // Seen 10/15 on the Lenovo and the 1280x800 frame: "15 s, from the south-southw…" ellipsized against "Gusts to 20".
        val stats = source.substring(source.indexOf("screen.stats.forEachIndexed"), source.indexOf("private fun tideSection"))
        assertTrue(stats.contains("stat.detail?.let { addView(kit.text(it, scale.label, color = ink, maxLines = 2)"))
        assertTrue(stats.contains("if (index > 0) marginStart = kit.dp(scale.gap / 2)"))
    }

    @Test
    fun `the tide axis places its hour labels inside the chart`() {
        // Seen 10/15: a tick near the left edge drew as "AM" and the last one as "9 A".
        assertTrue(source.contains("TouchTideAxis.labelStart(x(millis), text.measureText(label), w)"))
    }

    @Test
    fun `the degree mark hangs from the top of the numeral instead of sitting on its baseline`() {
        val number = source.substring(source.indexOf("val number = kit.text(water"), source.indexOf("\"Water temperature\""))
        assertTrue(number.contains("isBaselineAligned = false"))
        assertTrue(number.contains("topMargin = numberTop - inkInsets(degrees).first"))
        assertTrue("the numeral's empty line box above and below the digits is trimmed", number.contains("topMargin = -numberTop") && number.contains("bottomMargin = -numberBottom"))
    }
}
