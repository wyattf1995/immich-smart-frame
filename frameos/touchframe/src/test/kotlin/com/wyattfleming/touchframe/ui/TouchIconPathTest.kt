package com.wyattfleming.touchframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouchIconPathTest {
    // Touch's own icons plus the shared screens' icons and weather glyphs in :frame-ui.
    private val icons = listOf("touchframe/src/main/res/drawable", "frame-ui/src/main/res/drawable").flatMap { dir ->
        File(dir).listFiles { file -> (file.name.startsWith("ic_touch_") || file.name.startsWith("ic_wx_")) && file.name.endsWith(".xml") }.orEmpty().toList()
    }.sortedBy { it.name }
    private val number = Regex("""-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    @Test
    fun `icon paths never rely on implicit lineto after moveto, which API 23 draws as nothing`() {
        // Android 6's PathParser treats extra coordinate pairs after M/m as further movetos, so a
        // chevron written "m15 5-7 7 7 7" rendered blank on the small frame while API 27 drew it.
        assertTrue("icon set not found", icons.size >= 20)
        val offenders = icons.flatMap { file ->
            Regex("""android:pathData="([^"]+)"""").findAll(file.readText()).flatMap { path ->
                Regex("""[Mm][^A-Za-z]*""").findAll(path.groupValues[1])
                    .filter { number.findAll(it.value).count() > 2 }
                    .map { "${file.name}: ${it.value.trim()}" }
            }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
