package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouchShellChipOrderTest {
    private val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/ui/TouchShellLayout.kt").readText()

    private fun body(start: String, end: String): String {
        val from = source.indexOf(start)
        assertTrue("missing $start", from >= 0)
        return source.substring(from, source.indexOf(end, from))
    }

    @Test
    fun `tab chips are assigned before their padding so the inset background cannot erase it`() {
        listOf(
            body("private fun destinationTab(", "private fun settingsTab("),
            body("private fun settingsTab(", "private val TouchDestination.icon"),
        ).forEach { builder ->
            val background = builder.indexOf("background = insetChip(")
            val padding = builder.indexOf("setPadding(")
            assertTrue("chip background must be set", background >= 0)
            assertTrue("padding must follow the chip background", padding > background)
        }
    }
}
