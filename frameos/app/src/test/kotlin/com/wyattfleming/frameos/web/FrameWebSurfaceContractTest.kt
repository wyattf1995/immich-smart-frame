package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class FrameWebSurfaceContractTest {
    @Test
    fun `page load timeout stops Gecko and logs a redacted timeout event`() {
        val source = source()
        val armPageLoadWatchdog = methodBody(source, "private fun armPageLoadWatchdog(observedSession: GeckoSession)")

        assertContainsInOrder(
            armPageLoadWatchdog,
            listOf(
                "val startedAtMillis = pageLoadWatchdogState.markTimedOut(load.generation) ?: return@Runnable",
                "operationLogger.log(",
                "route = label",
                "stage = \"page_load\"",
                "outcome = \"timeout\"",
                "elapsedMillis = (elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)",
                "runCatching { observedSession.stop() }",
                "reportUnavailable(this)",
            ),
        )
        assertFalse("watchdog logging should not include the timed-out URL", armPageLoadWatchdog.contains("url ="))
    }

    @Test
    fun `suspendAllContent cancels recovery callbacks before suspending sessions without evicting warm home`() {
        val suspendAllContent = methodBody(source(), "fun suspendAllContent()")

        assertContainsInOrder(
            suspendAllContent,
            listOf(
                "allSessions().forEach {",
                "cancelRecovery(it)",
                "it.suspendForLifecycle()",
            ),
        )
        assertFalse(suspendAllContent.contains("evictHiddenHome()"))
        assertFalse(suspendAllContent.contains("hiddenHomeLifecycle.onEvicted()"))
    }

    @Test
    fun `Birds follows Cameras disposable lifecycle and is never preloaded`() {
        val source = source()
        assertContainsInOrder(
            source,
            listOf(
                "FrameWebSlot.BIRDS",
                "if (displayedSlot == FrameWebSlot.BIRDS)",
                "disposeBirdsSession()",
            ),
        )
        assertTrue("Birds must close its Gecko session", source.contains("birdsSession?.close()"))
    }

    @Test
    fun `Birds stops its attached session before detaching and defers only final close`() {
        val disposeBirdsSession = methodBody(source(), "private fun disposeBirdsSession()")

        assertContainsInOrder(
            disposeBirdsSession,
            listOf(
                "cancelRecovery(disposable)",
                "disposable.prepareForDisposal()",
                "if (foregroundSlot == FrameWebSlot.BIRDS)",
                "releaseForegroundAttachedSession()",
                "val close = Runnable",
                "disposable.close()",
                "post(close)",
            ),
        )
    }

    private fun source(): String {
        val candidates = listOf(
            Paths.get("app/src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt"),
            Paths.get("src/main/kotlin/com/wyattfleming/frameos/web/FrameWebSurface.kt"),
        )
        val existing = candidates.firstOrNull(Files::exists)
            ?: error("Missing source file in candidates: $candidates")
        return Files.readString(existing)
    }

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
