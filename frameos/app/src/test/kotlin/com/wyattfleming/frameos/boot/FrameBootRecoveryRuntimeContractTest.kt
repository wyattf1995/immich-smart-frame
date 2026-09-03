package com.wyattfleming.frameos.boot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class FrameBootRecoveryRuntimeContractTest {
    @Test
    fun `trusted boot schedules an immediate launch and two durable bounded retries`() {
        val source = source("app/src/main/kotlin/com/wyattfleming/frameos/boot/FrameBootRecoveryReceiver.kt")
        val onReceive = methodBody(source, "override fun onReceive(")
        val schedule = methodBody(source, "fun schedule(")

        assertContainsInOrder(
            onReceive,
            listOf(
                "FrameBootRecoveryPolicy.launchDelaysFor(intent.action)",
                "FrameBootRecoveryScheduler.schedule(context, delays)",
            ),
        )
        assertContainsInOrder(
            schedule,
            listOf(
                "cancelRetries(context)",
                "delaysMillis.forEachIndexed",
                "if (delayMillis == 0L)",
                "launch(context)",
                "setAndAllowWhileIdle(",
                "AlarmManager.ELAPSED_REALTIME_WAKEUP",
                "retryIntent(context, attemptIndex)",
            ),
        )
    }

    @Test
    fun `recovery launch is explicit permission gated and cancelled only after rendered content draws`() {
        val receiver = source("app/src/main/kotlin/com/wyattfleming/frameos/boot/FrameBootRecoveryReceiver.kt")
        val activity = source("app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt")
        val launch = methodBody(receiver, "fun launch(")
        val onNewIntent = methodBody(activity, "override fun onNewIntent(")
        val onResume = methodBody(activity, "override fun onResume()")
        val onPageRendered = methodBody(activity, "override fun onPageRendered(")
        val confirmAfterDraw = methodBody(activity, "private fun confirmBootRecoveryAfterNextDraw(")
        val completeAfterDraw = methodBody(activity, "private fun completeBootRecoveryAfterDraw(")
        val onPause = methodBody(activity, "override fun onPause()")

        assertContainsInOrder(
            launch,
            listOf(
                "Settings.canDrawOverlays(context)",
                "Intent(context, MainActivity::class.java)",
                ".setAction(ACTION_RETRY)",
                "Intent.FLAG_ACTIVITY_NEW_TASK",
                "context.startActivity(launchIntent)",
            ),
        )
        assertContainsInOrder(
            onNewIntent,
            listOf(
                "if (!bootRecoveryConfirmed && intent.action == FrameBootRecoveryScheduler.ACTION_RETRY)",
                "webSurface?.retryDisplayedContentIfUnrendered()",
            ),
        )
        assertFalse(
            "resume alone does not prove the frame rendered usable content",
            onResume.contains("FrameBootRecoveryScheduler.cancelRetries(this)"),
        )
        assertContainsInOrder(
            onPageRendered,
            listOf(
                "isWebLabelActive(label)",
                "webSurface?.isDisplayingRenderedLabel(label) == true",
                "confirmBootRecoveryAfterNextDraw(webLabel = label)",
            ),
        )
        assertContainsInOrder(
            confirmAfterDraw,
            listOf(
                "if (bootRecoveryConfirmed || !activityResumed",
                "root.viewTreeObserver.addOnDrawListener",
                "root.invalidate()",
            ),
        )
        assertContainsInOrder(
            completeAfterDraw,
            listOf(
                "root.post",
                "if (!activityResumed || bootRecoveryConfirmed)",
                "pendingWebLabel != null",
                "webSurface?.isDisplayingRenderedLabel(pendingWebLabel) != true",
                "FrameBootRecoveryScheduler.cancelRetries(this)",
                "bootRecoveryConfirmed = true",
            ),
        )
        assertTrue(
            "pausing before the confirming draw must preserve the pending retry budget",
            onPause.contains("cancelPendingBootRecoveryConfirmation()"),
        )
    }

    @Test
    fun `web native weather and configuration surfaces all have paint qualified recovery paths`() {
        val activity = source("app/src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt")
        val onResume = methodBody(activity, "override fun onResume()")
        val render = methodBody(activity, "private fun render(next: FrameState, announce: Boolean)")

        assertContainsInOrder(
            activity,
            listOf(
                "override fun onPageRendered(label: String)",
                "isWebLabelActive(label)",
                "webSurface?.isDisplayingRenderedLabel(label) == true",
                "confirmBootRecoveryAfterNextDraw()",
            ),
        )
        assertContainsInOrder(
            render,
            listOf(
                "FrameSurfaceTarget.NativeWeather",
                "weatherContent.visibility = View.VISIBLE",
                "confirmBootRecoveryAfterNextDraw()",
            ),
        )
        assertTrue(
            "the native configuration view must confirm after its own draw",
            onResume.contains("if (configuration == null) confirmBootRecoveryAfterNextDraw()"),
        )
    }

    private fun source(path: String): String {
        val candidates = listOf(Paths.get(path), Paths.get(path.removePrefix("app/")))
        return Files.readString(candidates.firstOrNull(Files::exists) ?: missing(candidates))
    }

    private fun missing(candidates: List<Path>): Nothing = error("Missing source file in candidates: $candidates")

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "Missing method body for: $signature" }
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("Unterminated method body for: $signature")
    }

    private fun assertContainsInOrder(source: String, snippets: List<String>) {
        var searchFrom = 0
        snippets.forEach { snippet ->
            val foundAt = source.indexOf(snippet, startIndex = searchFrom)
            assertTrue("Missing snippet in order: $snippet", foundAt >= 0)
            searchFrom = foundAt + snippet.length
        }
    }
}
