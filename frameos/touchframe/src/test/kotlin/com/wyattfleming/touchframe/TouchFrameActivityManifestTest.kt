package com.wyattfleming.touchframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * On October 14 the low-memory killer took Touch while it was on screen on the 1920×1080 frame, and Android resumed
 * the Settings task left behind it, which has no way back. `stateNotNeeded` is how a Home app tells Android to keep its
 * place and start it again instead; it is only safe while Touch keeps no instance state for a restart to lose.
 */
class TouchFrameActivityManifestTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val activity: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("touchframe/src/main/AndroidManifest.xml"))
        .getElementsByTagName("activity").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        .single { it.getAttributeNS(android, "name") == ".TouchFrameActivity" }

    @Test
    fun `Android restarts Touch in place when its process is killed on screen`() {
        assertEquals("true", activity.getAttributeNS(android, "stateNotNeeded"))
    }

    @Test
    fun `Touch keeps no instance state that a restart in place would lose`() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt").readText()
        assertFalse(source.contains("onSaveInstanceState") || source.contains("onRestoreInstanceState"))
    }
}
