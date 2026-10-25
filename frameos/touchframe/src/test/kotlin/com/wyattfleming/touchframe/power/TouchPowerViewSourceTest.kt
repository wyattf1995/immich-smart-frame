package com.wyattfleming.touchframe.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards what JVM tests cannot see in the Power view: on these frames every animated frame costs 14–21 ms, so the
 * page must stay still, and a text added to a wrap-content column without its own layout params gets clipped.
 */
class TouchPowerViewSourceTest {
    private val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/power/TouchPowerView.kt").readText()

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
    fun `the page never animates and the dial turns only with the minute`() {
        listOf("ValueAnimator", "ObjectAnimator", "postInvalidateOnAnimation", "Choreographer", ".animate()").forEach {
            assertFalse("Power must not animate ($it)", source.contains(it))
        }
        assertTrue(source.contains("const val SLOT_MILLIS = 60_000L"))
    }

    @Test
    fun `drawing allocates nothing, paths and positions are built when the data or size changes`() {
        val bodies = drawBodies()
        assertEquals("the dial, the usage bar and the laundry glyph", 3, bodies.size)
        bodies.forEach { body ->
            // Word boundaries, so drawPath( and drawRect( are not mistaken for allocations.
            listOf("Path", "RectF", "Paint", "ArrayList", "listOf", "floatArrayOf").forEach {
                assertFalse("onDraw allocates $it", Regex("""\b$it\(""").containsMatchIn(body))
            }
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
        assertTrue("expected the side column's texts, found $count", count >= 12)
    }
}
