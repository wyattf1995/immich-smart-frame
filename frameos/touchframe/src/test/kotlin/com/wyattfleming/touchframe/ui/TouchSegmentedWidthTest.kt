package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouchSegmentedWidthTest {
    private val source = File("frame-ui/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchTheme.kt").readText()

    private fun segmentedBody(): String {
        val from = source.indexOf("fun <T> segmented(")
        assertTrue("missing segmented()", from >= 0)
        return source.substring(from, source.indexOf("enum class Tone", from))
    }

    @Test
    fun `every segment starts at the widest label so a wrap-content control never truncates one`() {
        // Zero-width weighted segments in a wrap-content row get the AVERAGE label width on API 24+:
        // the wall frame showed "Age…" and a selected "Mon…".
        val body = segmentedBody()
        assertTrue("segments must be laid out from the shared widest-label width", body.contains("LinearLayout.LayoutParams(segmentWidth,"))
        assertFalse("zero-width weighted segments truncate on API 24+", body.contains("LinearLayout.LayoutParams(0,"))
        assertTrue("the widest label is measured in the bold selected face", body.contains("TouchFonts.Face.TEXT_BOLD"))
    }

    @Test
    fun `shared segment width fits the widest label plus both paddings`() {
        assertEquals(101 + 2 * 18 + 2, TouchSegmentSizing.width(listOf(80f, 100.2f, 64f), horizontalPaddingPx = 18, slackPx = 2))
        assertEquals(2 * 18, TouchSegmentSizing.width(emptyList(), horizontalPaddingPx = 18, slackPx = 0))
    }
}
