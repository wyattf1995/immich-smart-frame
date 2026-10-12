package com.wyattfleming.frameos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MainActivityFullscreenMediaContractTest {
    @Test
    fun `render computes media navigation before showing web content and updates only layout margins`() {
        val source = mainActivity()
        val render = methodBody(source, "private fun render(next: FrameState, announce: Boolean)")
        val update = methodBody(source, "private fun updateTouchNavigation()")

        assertTrue(render.trimStart().startsWith("{\n        updateTouchNavigation()"))
        assertContainsInOrder(
            update,
            listOf(
                "touchMediaNavigation.setScreen(touchDashboardEnabled && configuration != null, dashboardVisible, state.mode)",
                "val inset = if (touchMediaNavigation.contentHasRailInset) dp(88) else 0",
                "insetContentViews.forEach { view ->",
                "params.leftMargin = inset",
                "view.layoutParams = params",
                "dismissTouchMenu?.visibility = if (touchMediaNavigation.dismissLayerVisible) View.VISIBLE else View.GONE",
                "touchRail?.visibility = if (touchMediaNavigation.railVisible) View.VISIBLE else View.GONE",
            ),
        )
        assertFalse("fullscreen must adjust margins rather than reparenting Gecko views", update.contains("removeView("))
    }

    @Test
    fun `immersive media uses only inward left edge to reveal a dismissible overlay`() {
        val source = mainActivity()
        val buildUi = methodBody(source, "private fun buildUi()")

        assertContainsInOrder(
            buildUi,
            listOf(
                "if (touchMediaNavigation.immersiveMedia)",
                "touchMediaNavigation.revealMenu()",
                "updateTouchNavigation()",
                "shouldRoute = { direction ->",
                "oauthSurface == null && touchMediaNavigation.acceptsEdgeSwipe(direction)",
            ),
        )
        assertContainsInOrder(
            buildUi,
            listOf(
                "dismissTouchMenu = View(this).apply {",
                "contentDescription = \"Close navigation menu\"",
                "touchMediaNavigation.dismissMenu()",
                "root.addView(it, FrameLayout.LayoutParams(",
                "touchRail?.bringToFront()",
            ),
        )
    }

    @Test
    fun `explicit mode and Today navigation discard a stale media menu`() {
        val source = mainActivity()
        val showMode = methodBody(source, "private fun showMode(mode: FrameMode, announceHud: Boolean = true)")
        val showDashboard = methodBody(source, "private fun showDashboard()")

        assertContainsInOrder(showMode, listOf("touchMediaNavigation.dismissMenu()", "dashboardVisible = false", "render(state, announce = changed)"))
        assertContainsInOrder(showDashboard, listOf("touchMediaNavigation.dismissMenu()", "dashboardVisible = true", "render(state, announce = false)"))
    }

    private fun mainActivity(): String {
        val root = Path.of("src/main/kotlin/com/wyattfleming/frameos")
        val source = root.resolve("MainActivity.kt")
        require(Files.isRegularFile(source)) { "Missing MainActivity source" }
        return Files.readString(source)
    }

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) when (source[index]) {
            '{' -> depth += 1
            '}' -> if (--depth == 0) return source.substring(open, index + 1)
        }
        error("Unterminated method: $signature")
    }

    private fun assertContainsInOrder(source: String, snippets: List<String>) {
        var offset = 0
        snippets.forEach { snippet ->
            val found = source.indexOf(snippet, offset)
            assertTrue("Missing in order: $snippet", found >= 0)
            offset = found + snippet.length
        }
    }
}
