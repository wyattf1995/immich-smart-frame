package com.wyattfleming.touchframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wyatt (10/17): a screen the user opens stays until they choose another; Photos is one tap away. The frame used to
 * return to Photos after the companion's idle delay. The Photos page is hidden while another screen covers it and
 * released after two minutes away, so a screen left up for hours does not hold the page's memory.
 */
class TouchStayOnScreenSourceTest {
    private val activity = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt").readText()
    private val media = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()

    private fun body(start: String): String {
        val from = activity.indexOf(start)
        assertTrue("missing $start", from >= 0)
        return activity.substring(from, activity.indexOf("\n    }\n", from))
    }

    private fun assertInOrder(source: String, snippets: List<String>) {
        var from = 0
        snippets.forEach { snippet ->
            val at = source.indexOf(snippet, from)
            assertTrue("missing in order: $snippet", at >= 0)
            from = at + snippet.length
        }
    }

    @Test
    fun `the screen the user picked stays until they pick another`() {
        assertFalse("no idle return to Photos", activity.contains("TouchIdleReturnPolicy"))
        assertFalse("the display tick only dims and resumes photos", body("private fun updateDisplayBehavior(").contains("navigate("))
    }

    @Test
    fun `leaving the page hides it, and two minutes away release it`() {
        val navigate = body("private fun navigate(next: TouchDestination)")
        assertInOrder(navigate, listOf(
            "val leavingMedia = destination.usesWebMedia && !next.usesWebMedia",
            "media?.visibility = if (TouchMediaRetention.visible(next)) View.VISIBLE else View.INVISIBLE",
            "if (next.usesWebMedia) main.removeCallbacks(releaseMedia)",
            "else if (leavingMedia && media != null) {",
            "leftMediaAt = SystemClock.elapsedRealtime()",
            "main.postDelayed(releaseMedia, TouchMediaRetention.RELEASE_AFTER_MILLIS)",
        ))
        assertInOrder(body("private fun releaseMediaIfAway()"), listOf(
            "TouchMediaRetention.shouldRelease(destination, SystemClock.elapsedRealtime() - leftMediaAt)",
            "media = null",
            "surface.close()",
            "shell.content.removeView(surface)",
        ))
    }

    @Test
    fun `Photos keeps the TextureView backend that reports its first paint on the frames`() {
        // Build 40 (10/17) tried BACKEND_SURFACE_VIEW: on the 159K's Android 8.1 Gecko never reported first contentful
        // paint, so every load timed out into the offline photos with Retry. Hiding the page under native screens
        // (above) is what stops it drawing there.
        assertTrue(media.contains("setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)"))
        assertFalse(media.contains("BACKEND_SURFACE_VIEW"))
    }
}
