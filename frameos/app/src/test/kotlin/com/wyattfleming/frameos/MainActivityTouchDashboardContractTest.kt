package com.wyattfleming.frameos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MainActivityTouchDashboardContractTest {
    @Test
    fun `startup reads the local touchscreen opt in and physical touch is the dashboard front door`() {
        val source = mainActivity()
        val settings = touchscreenSettings()
        val receiver = controlReceiver()

        assertContainsInOrder(
            source,
            listOf(
                "touchscreenStore = FrameTouchscreenSettingsStore(this)",
                "touchscreenSettings = touchscreenStore.read()",
                "dashboardVisible = touchDashboardEnabled && !touchscreenSettings.startWithPhotos",
            ),
        )
        assertTrue(source.contains("packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)"))
        assertTrue(settings.contains("fun showsDashboard(hasTouchscreen: Boolean): Boolean = dashboardEnabled && hasTouchscreen"))
        assertTrue(settings.contains("val startWithPhotos: Boolean = false"))
        assertTrue(receiver.contains("FrameTouchscreenSettingsStore(context)"))
        assertTrue(controlContract().contains("EXTRA_START_WITH_PHOTOS = \"frameos.start_with_photos\""))
        assertTrue(receiver.contains("startWithPhotos = intent.getBooleanExtra(FrameControlContract.EXTRA_START_WITH_PHOTOS, current.startWithPhotos)"))
        assertFalse("hardware preferences must not be read from companion state", receiver.substringBefore("val configurationStore").contains("FrameCompanion"))
    }

    @Test
    fun `dashboard render hides and suspends browser content`() {
        val render = methodBody(mainActivity(), "private fun render(next: FrameState, announce: Boolean)")

        assertContainsInOrder(
            render,
            listOf(
                "if (dashboardVisible && touchDashboard != null)",
                "photoBridge.setCaptureEnabled(false, true)",
                "hideWebRecovery()",
                "weatherContent.visibility = View.GONE",
                "webSurface?.hide()",
                "webSurface?.suspendAllContent()",
                "handler.removeCallbacks(preloadCalendar)",
            ),
        )
    }

    @Test
    fun `Photos and directional mode routes can leave the dashboard`() {
        val source = mainActivity()
        val showMode = methodBody(source, "private fun showMode(mode: FrameMode, announceHud: Boolean = true)")
        val dispatch = methodBody(source, "private fun dispatch(intent: FrameIntent)")

        assertContainsInOrder(
            showMode,
            listOf(
                "dashboardVisible = false",
                "cancelDashboardPhoto()",
                "render(state, announce = changed)",
            ),
        )
        assertContainsInOrder(
            dispatch,
            listOf(
                "if (intent == FrameIntent.IdleExpired && state.mode.persistsAcrossIdle) return",
                "if (touchDashboardEnabled && (intent == FrameIntent.GoHome || intent == FrameIntent.IdleExpired))",
            ),
        )
        assertTrue(dispatch.contains("if (intent == FrameIntent.NextMode || intent == FrameIntent.PreviousMode) dashboardVisible = false"))
        assertTrue(source.contains("onOpenMode = { showMode(it, announceHud = false) }"))
    }

    @Test
    fun `current dashboard photo completion clears its future before rejecting its entry`() {
        val refresh = methodBody(mainActivity(), "private fun refreshDashboardPhoto()")

        assertContainsInOrder(
            refresh,
            listOf(
                "if (generation == dashboardPhotoGeneration) dashboardPhotoFuture = null",
                "if (generation != dashboardPhotoGeneration || !activityResumed || !dashboardVisible ||",
                "photoReserve.entries().none { it.assetId == entry.assetId }",
                "bitmap?.recycle()",
                "return@post",
            ),
        )
    }

    private fun mainActivity(): String = readSource("MainActivity.kt")
    private fun touchscreenSettings(): String = readSource("config/FrameTouchscreenSettings.kt")
    private fun controlReceiver(): String = readSource("control/FrameControlReceiver.kt")
    private fun controlContract(): String = readSource("control/FrameControlCommand.kt")

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
