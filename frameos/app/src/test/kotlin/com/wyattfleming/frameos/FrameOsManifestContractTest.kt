package com.wyattfleming.frameos

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FrameOsManifestContractTest {
    @Test
    fun `MainActivity remains eligible for launcher and default Home selection`() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { index -> activities.item(index) as Element }
            .single { activity -> activity.getAttribute("android:name") == ".MainActivity" }

        val filters = mainActivity.getElementsByTagName("intent-filter")
        val categories = (0 until filters.length)
            .map { index -> filters.item(index) as Element }
            .filter { filter ->
                filter.getElementsByTagName("action").elements().any { action ->
                    action.getAttribute("android:name") == "android.intent.action.MAIN"
                }
            }
            .flatMap { filter ->
                filter.getElementsByTagName("category").elements().map { category ->
                    category.getAttribute("android:name")
                }
            }

        assertTrue("FrameOS must retain launcher eligibility", "android.intent.category.LAUNCHER" in categories)
        assertTrue("FrameOS must be selectable as Home", "android.intent.category.HOME" in categories)
        assertTrue("FrameOS Home intent must be a default resolution candidate", "android.intent.category.DEFAULT" in categories)
    }

    private fun org.w3c.dom.NodeList.elements(): List<Element> =
        (0 until length).map { index -> item(index) as Element }
}
