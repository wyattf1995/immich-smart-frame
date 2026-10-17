package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Latency audit 10/17 on the large frame: a return to Photos showed black for 4-4.5 s whenever the page had to load
 * (after the 2-minute release, or when the kernel had killed the hidden page 6 s after leaving Photos), and a cold
 * start 7-8 s. Wyatt's budgets: a picture within 1 s of returning, within 3 s of a cold start.
 */
class TouchPhotosReturnSourceTest {
    private val media = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()

    private fun body(start: String): String {
        val from = media.indexOf(start)
        assertTrue("missing $start", from >= 0)
        return media.substring(from, media.indexOf("\n    }\n", from))
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
    fun `a hidden Photos page asks Gecko to keep its process, a shown one returns to the default`() {
        assertInOrder(body("fun setVisibleActive(active: Boolean)"), listOf(
            "current.setActive(active)",
            "current.setPriorityHint(if (active) GeckoSession.PRIORITY_DEFAULT else GeckoSession.PRIORITY_HIGH)",
        ))
    }

    @Test
    fun `a loading Photos page shows the newest reserve photo until Kiosk shows a live one`() {
        assertInOrder(body("private fun showReserveWhileLoading()"), listOf(
            "if (!active || photosProfile == null || loadingCover.visibility != View.VISIBLE) return",
            "offlinePhotos.showFallback()",
            "reserveShownForLoad = true",
        ))
        assertInOrder(body("fun setVisibleActive(active: Boolean)"), listOf(
            "offlinePhotos.setActive(active && photosProfile != null)",
            "armRenderDeadline(renderDeadline.foregrounded())",
            "showReserveWhileLoading()",
        ))
        assertInOrder(body("private fun loadConfiguredUrl(url: String)"), listOf("session?.loadUri(url)", "showReserveWhileLoading()"))
    }

    @Test
    fun `the reserve photo leaves within ten seconds of first paint even if Kiosk never reports a photo`() {
        assertEquals(10_000L, TouchMediaRetention.RESERVE_HANDOFF_MILLIS)
        assertInOrder(body("private fun reportReady(source: GeckoSession)"), listOf(
            "loadingCover.visibility = View.GONE",
            "if (reserveShownForLoad) postDelayed(reserveHandoff, TouchMediaRetention.RESERVE_HANDOFF_MILLIS)",
        ))
        // A failure keeps its own fallback: Retry is up, so the hand-off must not hide it.
        assertInOrder(body("private fun reportFailure(source: GeckoSession, message: String)"), listOf(
            "reserveShownForLoad = false",
            "removeCallbacks(reserveHandoff)",
            "offlinePhotos.showFallback()",
        ))
    }
}
