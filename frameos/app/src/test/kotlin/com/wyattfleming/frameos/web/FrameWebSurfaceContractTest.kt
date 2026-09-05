package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class FrameWebSurfaceContractTest {
    @Test
    fun `fragment routing preserves existing document evidence before updating request identity`() {
        val request = methodBody(source(), "fun request(url: String)")
        assertContainsInOrder(
            request,
            listOf(
                "val fragmentOnly = loadState.isExactFragmentOnlyRequest(url)",
                "loadState.recordRequest(url)",
                "if (fragmentOnly)",
                "renderedState.recordFragmentOnlyRequest()",
                "} else {",
                "renderedState.recordRequest()",
                "session.loadUri(url)",
            ),
        )
    }

    @Test
    fun `render readiness requires both a successful stop and contentful paint`() {
        val source = source()
        val pageStart = methodBody(source, "override fun onPageStart(")
        val pageStop = methodBody(source, "override fun onPageStop(")
        val firstContentfulPaint = methodBody(source, "override fun onFirstContentfulPaint(")
        val paintStatusReset = methodBody(source, "override fun onPaintStatusReset(")
        val request = methodBody(source, "fun request(url: String)")
        val maybeReportRendered = methodBody(source, "private fun maybeReportRendered(")

        assertTrue(
            "listeners need a distinct paint-qualified readiness callback",
            source.contains("fun onPageRendered(label: String)"),
        )
        assertContainsInOrder(
            request,
            listOf(
                "loadState.recordRequest(url)",
                "renderedState.recordRequest()",
                "session.loadUri(url)",
            ),
        )
        assertContainsInOrder(
            pageStart,
            listOf(
                "renderedState.recordPageStart()",
                "listener.onPageRenderInvalidated(label)",
                "armPageLoadWatchdog(session)",
            ),
        )
        assertContainsInOrder(
            pageStop,
            listOf(
                "if (!success)",
                "renderedState.recordFailure()",
                "renderedState.recordPageStop(success = true)",
                "maybeReportRendered(",
            ),
        )
        assertFalse(
            "a successful network load is not usable until Gecko paints it",
            pageStop.contains("resetRecovery(") || pageStop.contains("listener.onPageRecovered("),
        )
        assertFalse(
            "the page watchdog must remain armed after page stop until paint qualifies",
            pageStop.substringAfter("} else {").contains("disarmPageLoadWatchdog()"),
        )
        assertContainsInOrder(
            firstContentfulPaint,
            listOf(
                "renderedState.recordFirstContentfulPaint()",
                "maybeReportRendered(",
            ),
        )
        assertContainsInOrder(
            paintStatusReset,
            listOf(
                "val invalidatedRenderedContent = renderedState.recordPaintStatusReset()",
                "listener.onPageRenderInvalidated(label)",
                "if (!invalidatedRenderedContent) return",
                "lifecycleSuspended ||",
                "this@ManagedSession !== displayedManagedSession()",
                "!displayedSurfaceVisible()",
                "loadState.recordFailure()",
                "return",
                "armPageLoadWatchdog(session)",
            ),
        )
        assertContainsInOrder(
            maybeReportRendered,
            listOf(
                "if (!newlyRendered) return",
                "val pageLoad = disarmPageLoadWatchdog()",
                "if (pageLoad.timedOut)",
                "renderedState.recordFailure()",
                "loadState.recordSuccess()",
                "resetRecovery(this)",
                "listener.onPageRecovered(label)",
                "reportRenderedIfDisplayed(this)",
            ),
        )
    }

    @Test
    fun `Photos waits for its current bridge attachment before loading and drops hidden pending work`() {
        val source = source()
        val request = methodBody(source, "fun request(url: String)")
        val setActive = methodBody(source, "fun setActive(active: Boolean)")
        val suspendForLifecycle = methodBody(source, "fun suspendForLifecycle()")

        assertContainsInOrder(
            request,
            listOf(
                "photoLoadGate.isReady()",
                "photoLoadGate.defer(url)",
                "return true",
                "session.loadUri(url)",
            ),
        )
        assertTrue(source.contains("photoLoadGate.beginAttachment()"))
        assertTrue(source.contains("photoLoadGate.markReady(attachmentEpoch)"))
        assertTrue(source.contains("photoLoadGate.takePendingIfReady(attachmentEpoch)"))
        assertTrue(source.contains("canFlushPendingPhotoRequest()"))
        assertTrue(setActive.contains("dropPendingPhotoRequest()"))
        assertTrue(suspendForLifecycle.contains("dropPendingPhotoRequest()"))
    }

    @Test
    fun `failed Photos registration retries through the next recovery request`() {
        val request = methodBody(source(), "fun request(url: String)")

        assertContainsInOrder(
            request,
            listOf(
                "photoLoadGate.isFailed()",
                "attachPhotoBridge()",
                "restorePhotoBridgeState()",
                "photoLoadGate.defer(url)",
                "return true",
            ),
        )
    }

    @Test
    fun `boot retry actively reloads only an unrendered displayed web session`() {
        val retry = methodBody(source(), "fun retryDisplayedContentIfUnrendered()")

        assertContainsInOrder(
            retry,
            listOf(
                "val managed = displayedManagedSession() ?: return false",
                "if (managed.renderedState.rendered) return false",
                "val url = managed.loadState.recoveryUrl() ?: return false",
                "cancelRecovery(managed)",
                "managed.request(url)",
            ),
        )
    }

    @Test
    fun `hidden or inactive content cannot retain a paint recovery watchdog`() {
        val source = source()
        val setActive = methodBody(source, "fun setActive(active: Boolean)")
        val stopWatching = methodBody(source, "fun stopWatchingDisplayedContent()")
        val show = methodBody(source, "fun show(slot: FrameWebSlot, url: String, takeFocus: Boolean)")
        val hide = methodBody(source, "fun hide()")

        assertContainsInOrder(
            setActive,
            listOf(
                "if (active)",
                "lifecycleSuspended = false",
                "else",
                "lifecycleSuspended = true",
                "stopWatchingDisplayedContent()",
                "session.setActive(active)",
            ),
        )
        assertContainsInOrder(
            stopWatching,
            listOf(
                "val pageLoad = disarmPageLoadWatchdog()",
                "if (pageLoad.hadActiveLoad && !renderedState.rendered)",
                "loadState.recordFailure()",
            ),
        )
        assertContainsInOrder(
            show,
            listOf(
                "if (displayedSlot != null && displayedSlot != slot)",
                "displayedManagedSession()?.stopWatchingDisplayedContent()",
            ),
        )
        assertTrue(hide.contains("displayedManagedSession()?.stopWatchingDisplayedContent()"))
    }

    @Test
    fun `only the displayed rendered session can report boot readiness`() {
        val source = source()
        val show = methodBody(source, "fun show(slot: FrameWebSlot, url: String, takeFocus: Boolean)")
        val reportRendered = methodBody(source, "private fun reportRenderedIfDisplayed(")

        assertContainsInOrder(
            show,
            listOf(
                "displayedSlot = slot",
                "managed.setActive(true)",
                "managed.setFocused(takeFocus)",
                "reportRenderedIfDisplayed(managed)",
            ),
        )
        assertContainsInOrder(
            reportRendered,
            listOf(
                "managed.renderedState.rendered",
                "managed === displayedManagedSession()",
                "displayedSurfaceVisible()",
                "listener.onPageRendered(managed.label)",
            ),
        )
    }

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
