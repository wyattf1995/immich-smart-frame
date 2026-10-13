package com.wyattfleming.frameos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Compatibility filename retained while the native dashboard integration is retired. */
class MainActivityTouchDashboardContractTest {
    @Test
    fun `touch startup opens authenticated Home unless Photos startup is configured`() {
        val source = mainActivity()

        assertContainsInOrder(
            source,
            listOf(
                "touchscreenSettings = touchscreenStore.read()",
                "if (touchNavigationEnabled && !touchscreenSettings.startWithPhotos) {",
                "state = state.copy(mode = FrameMode.HOME)",
            ),
        )
        assertTrue(source.contains("packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)"))
        assertFalse(source.contains("dashboardVisible"))
    }

    @Test
    fun `rail presents accessible Home and Android Settings return without a native Today destination`() {
        val source = mainActivity()
        val buildUi = methodBody(source, "private fun buildUi()")
        val settingsReturn = readSource("ui/FrameExternalSettingsReturnController.kt")
        val rail = readSource("ui/TouchNavigationRail.kt")
        val modes = readSource("navigation/FrameMode.kt")

        assertContainsInOrder(
            buildUi,
            listOf(
                "TouchNavigationRail(this,",
                "onOpenSettings = { openAndroidSettings() }",
                "onOpenMode = { showMode(it, announceHud = false) }",
                "it.setModes(TouchNavigationRailPolicy.ordered(availableModes()))",
            ),
        )
        assertContainsInOrder(
            source,
            listOf(
                "private fun openAndroidSettings()",
                "settingsReturnController.openSettings()",
            ),
        )
        assertContainsInOrder(
            settingsReturn,
            listOf(
                "fun openSettings(): Outcome",
                "adapter.showReturn(::returnToFrame)",
                "adapter.launchAndroidSettings()",
                "private fun returnToFrame()",
                "if (adapter.launchFrame()) remove()",
            ),
        )
        assertTrue(modes.contains("HOME(\"Home\")"))
        assertContainsInOrder(
            rail,
            listOf(
                "modes.forEach { mode -> items.addView(item(mode, mode.label, selected == mode) { onOpenMode(mode) }) }",
                "contentDescription = label; isFocusable = true; isClickable = true",
                "setOnClickListener { action() }",
            ),
        )
        assertFalse(source.contains("TouchDashboardView(this"))
    }

    @Test
    fun `Back and Home controls route to the existing Home mode`() {
        val source = mainActivity()
        val resume = methodBody(source, "override fun onResume()")
        val pause = methodBody(source, "override fun onPause()")
        val back = methodBody(source, "override fun onBackPressed()")
        val dispatch = methodBody(source, "private fun dispatch(intent: FrameIntent)")

        assertContainsInOrder(resume, listOf(
            "unregisterHomeBack?.invoke()",
            "FrameHomeBackNavigation.register(this)",
            "if (oauthSurface != null) closeWeatherAuthorization(consumeState = true)",
            "else showMode(FrameMode.HOME, announceHud = false)",
        ))
        assertContainsInOrder(pause, listOf("unregisterHomeBack?.invoke()", "unregisterHomeBack = null"))
        assertTrue(back.contains("touchNavigationEnabled && configuration != null"))
        assertTrue(back.contains("showMode(FrameMode.HOME, announceHud = false)"))
        assertContainsInOrder(dispatch, listOf(
            "if (intent == FrameIntent.IdleExpired && state.mode.persistsAcrossIdle) return",
            "if (touchNavigationEnabled && (intent == FrameIntent.GoHome || intent == FrameIntent.IdleExpired))",
            "showMode(FrameMode.HOME, announceHud = false)",
        ))
    }

    @Test
    fun `render always selects a real mode and keeps native Weather separate`() {
        val render = methodBody(mainActivity(), "private fun render(next: FrameState, announce: Boolean)")

        assertContainsInOrder(render, listOf("touchRail?.setSelection(next.mode)", "FrameSurfaceTarget.NativeWeather ->"))
        assertFalse(render.contains("suspendAllContent()"))
    }

    private fun mainActivity(): String = readSource("MainActivity.kt")

    private fun readSource(relative: String): String {
        val root = Path.of("src/main/kotlin/com/wyattfleming/frameos")
        val file = root.resolve(relative)
        require(Files.isRegularFile(file)) { "Missing source: $file" }
        return Files.readString(file)
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
