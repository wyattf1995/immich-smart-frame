package com.wyattfleming.touchframe.media

import com.wyattfleming.frameos.security.FrameUrlPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMediaLoadStateTest {
    private val art = "https://photos.example/art"
    private val photos = "https://photos.example/frame"

    @Test
    fun initialEntryDoesNotArmRetainedKillRecovery() {
        val state = TouchMediaLoadState()
        state.showUrl(art)
        state.visibilityChanged(true, 1)
        assertNull(state.recoverForegroundKill(1))
    }

    @Test
    fun delayedKillAfterWarmReturnRecoversOnceWithoutPaintRearming() {
        val state = returnedArt()
        assertEquals(TouchMediaLoadState.Decision.ALREADY_LOADED, state.showUrl(art))
        assertEquals(art, state.recoverForegroundKill(1))
        assertNull(state.recoverForegroundKill(1))
        assertNull(state.recoverForegroundKill(2))
        state.markContentProcessClosed()
        assertEquals(TouchMediaLoadState.Decision.RETRY_REQUIRED, state.showUrl(art))
    }

    @Test
    fun activeCrashAndExplicitRetryClearTheReturnTicket() {
        val crashed = returnedArt()
        crashed.markContentProcessClosed()
        assertNull(crashed.recoverForegroundKill(1))
        assertEquals(TouchMediaLoadState.Decision.RETRY_REQUIRED, crashed.showUrl(art))
        val retried = returnedArt()
        assertEquals(art, retried.retry()?.url)
        assertNull(retried.recoverForegroundKill(1))
    }

    @Test
    fun crossUrlReplacementDiscardsTheOldReturnTicket() {
        val state = returnedArt()
        assertEquals(TouchMediaLoadState.Decision.REPLACE_DOCUMENT_SESSION, state.showUrl(photos))
        assertNull(state.recoverForegroundKill(1))
        assertNull(state.recoverForegroundKill(2))
    }

    @Test
    fun hiddenDeliveredClosureConsumesAllRecoveryBeforeReplacement() {
        val state = returnedArt()
        state.visibilityChanged(false, 1)
        state.markContentProcessClosed(whileHidden = true)
        state.visibilityChanged(true, 1)
        assertNull(state.recoverForegroundKill(1))
        assertEquals(TouchMediaLoadState.Decision.RECOVER_RETAINED_SESSION, state.showUrl(art))
        assertNull(state.recoverForegroundKill(2))
        state.markContentProcessClosed()
        assertEquals(TouchMediaLoadState.Decision.RETRY_REQUIRED, state.showUrl(art))
    }

    @Test
    fun hideClearsTheOldTicketAndTheNextRealReturnGetsOneChance() {
        val state = returnedArt()
        state.visibilityChanged(false, 1)
        assertNull(state.recoverForegroundKill(1))
        state.visibilityChanged(true, 1)
        assertEquals(art, state.recoverForegroundKill(1))
        assertNull(state.recoverForegroundKill(1))
    }

    @Test
    fun retainedTicketCannotRecoverADifferentSessionGeneration() {
        assertNull(returnedArt().recoverForegroundKill(2))
        val changedWhileHidden = TouchMediaLoadState()
        changedWhileHidden.showUrl(art)
        changedWhileHidden.visibilityChanged(false, 1)
        changedWhileHidden.visibilityChanged(true, 2)
        assertNull(changedWhileHidden.recoverForegroundKill(2))
    }

    @Test
    fun productionKillRecoveryIsIdentityFencedAndCrashRemainsManual() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()
        assertTrue(source.contains("loadState.visibilityChanged(active, sessionGeneration)"))
        assertTrue(source.contains("override fun onKill(session: GeckoSession) = reportContentProcessKilled(session)"))
        assertTrue(source.contains("override fun onCrash(session: GeckoSession) = reportContentProcessClosed(session)"))
        val killed = source.substringAfter("private fun reportContentProcessKilled(source: GeckoSession)")
            .substringBefore("private fun reportContentProcessClosed")
        assertTrue(killed.indexOf("if (!isCurrentSession(source)) return") in 0 until killed.indexOf("loadState.recoverForegroundKill(sessionGeneration)"))
        assertTrue(killed.contains("if (url != null) reopenAndLoad(url) else reportContentProcessClosed(source)"))
        val ready = source.substringAfter("private fun reportReady(source: GeckoSession)").substringBefore("private fun reportFailure")
        assertFalse(ready.contains("loadState."))
    }

    private fun returnedArt(): TouchMediaLoadState = TouchMediaLoadState().apply {
        showUrl(art)
        visibilityChanged(true, 1)
        visibilityChanged(false, 1)
        visibilityChanged(true, 1)
    }

    @Test
    fun sameConfiguredUrlStaysWarmButChangedUrlReplacesItsDocumentSession() {
        val state = TouchMediaLoadState()

        assertEquals(TouchMediaLoadState.Decision.LOAD, state.showUrl("https://photos.example/frame"))
        assertEquals(TouchMediaLoadState.Decision.ALREADY_LOADED, state.showUrl("https://photos.example/frame"))
        assertEquals(TouchMediaLoadState.Decision.REPLACE_DOCUMENT_SESSION, state.showUrl("https://photos.example/art"))
        assertEquals(TouchMediaLoadState.Decision.ALREADY_LOADED, state.showUrl("https://photos.example/art"))
    }

    @Test
    fun crossUrlProductionPathCoversThenRetiresThePreviousSession() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()
        val replace = source.substring(
            source.indexOf("private fun reopenAndLoad(url: String)"),
            source.indexOf("private fun loadConfiguredUrl", startIndex = source.indexOf("private fun reopenAndLoad(url: String)")),
        )

        assertTrue(source.contains("TouchMediaLoadState.Decision.REPLACE_DOCUMENT_SESSION -> {\n                reopenAndLoad(url)"))
        val cover = replace.indexOf("loadingCover.visibility = View.VISIBLE")
        val hiddenView = replace.indexOf("geckoView.visibility = View.INVISIBLE")
        val replaced = replace.indexOf("val replaced = session")
        val clearedIdentity = replace.indexOf("session = null")
        val closed = replace.indexOf("current.close()")
        val opened = replace.indexOf("openSession()")
        listOf(cover, hiddenView, replaced, clearedIdentity, closed, opened).forEach { index ->
            assertTrue("cross-URL session marker is required", index >= 0)
        }
        assertTrue(cover < replaced)
        assertTrue(hiddenView < replaced)
        assertTrue(replaced < clearedIdentity)
        assertTrue(clearedIdentity < closed)
        assertTrue(closed < opened)
    }

    @Test
    fun contentCrashRequiresExplicitRetryAndUsesTheMostRecentConfiguredUrl() {
        val state = TouchMediaLoadState()
        state.showUrl("https://photos.example/frame")
        state.markContentProcessClosed()

        assertEquals(TouchMediaLoadState.Decision.RETRY_REQUIRED, state.showUrl("https://photos.example/art"))
        val retry = state.retry()
        assertEquals("https://photos.example/art", retry?.url)
        assertTrue(retry?.reopenSession == true)
        assertEquals(TouchMediaLoadState.Decision.ALREADY_LOADED, state.showUrl("https://photos.example/art"))
    }

    @Test
    fun hiddenContentCrashRecoversExactlyOnceWhenTheUserReturns() {
        val state = TouchMediaLoadState()
        val art = "https://photos.example/art"
        assertEquals(TouchMediaLoadState.Decision.LOAD, state.showUrl(art))
        state.markContentProcessClosed(whileHidden = true)

        assertEquals(TouchMediaLoadState.Decision.RECOVER_RETAINED_SESSION, state.showUrl(art))
        assertEquals(TouchMediaLoadState.Decision.ALREADY_LOADED, state.showUrl(art))

        // The recovery session is foregrounded before it loads. A later crash
        // must remain user-controlled instead of starting an automatic loop.
        state.markContentProcessClosed()
        assertEquals(TouchMediaLoadState.Decision.RETRY_REQUIRED, state.showUrl(art))
        assertTrue(state.retry()?.reopenSession == true)
    }

    @Test
    fun successfulNavigationWaitsForContentfulPaintBeforeRemovingTheCover() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()

        assertTrue(source.contains("if (!success) reportFailure(session, context.getString(R.string.touch_media_load_failed, label))"))
        assertTrue(source.contains("override fun onFirstContentfulPaint(session: GeckoSession) = reportReady(session)"))
        assertFalse(source.contains("if (success) reportReady(session)"))
    }

    @Test
    fun foregroundLoadingExpiresOnceAndDoesNotRearmAfterTimeout() {
        val deadline = TouchMediaRenderDeadline()
        val load = TouchMediaRenderDeadline.LoadToken(loadGeneration = 4, sessionGeneration = 2)
        val scheduled = requireNotNull(deadline.beginLoading(load, foreground = true))

        assertTrue(deadline.expire(scheduled))
        assertFalse(deadline.expire(scheduled))
        assertNull(deadline.foregrounded())
    }

    @Test
    fun hiddenLoadingCancelsDeadlineAndRearmerGetsFreshForegroundWait() {
        val deadline = TouchMediaRenderDeadline()
        val load = TouchMediaRenderDeadline.LoadToken(loadGeneration = 7, sessionGeneration = 3)

        assertNull(deadline.beginLoading(load, foreground = false))
        val firstWait = requireNotNull(deadline.foregrounded())
        deadline.hidden()
        assertFalse(deadline.expire(firstWait))
        val rearmedWait = requireNotNull(deadline.foregrounded())
        assertFalse(firstWait == rearmedWait)
        assertFalse(deadline.expire(firstWait))
        assertTrue(deadline.expire(rearmedWait))
    }

    @Test
    fun readinessFailureAndSessionReplacementCancelTheDeadline() {
        val deadline = TouchMediaRenderDeadline()
        val ready = TouchMediaRenderDeadline.LoadToken(loadGeneration = 1, sessionGeneration = 1)
        val readyWait = requireNotNull(deadline.beginLoading(ready, foreground = true))
        deadline.ready()
        assertFalse(deadline.expire(readyWait))

        val failed = TouchMediaRenderDeadline.LoadToken(loadGeneration = 2, sessionGeneration = 1)
        val failedWait = requireNotNull(deadline.beginLoading(failed, foreground = true))
        deadline.failed()
        assertFalse(deadline.expire(failedWait))

        val replaced = TouchMediaRenderDeadline.LoadToken(loadGeneration = 3, sessionGeneration = 1)
        val replacedWait = requireNotNull(deadline.beginLoading(replaced, foreground = true))
        deadline.sessionReplaced()
        assertFalse(deadline.expire(replacedWait))

        val closed = TouchMediaRenderDeadline.LoadToken(loadGeneration = 4, sessionGeneration = 2)
        val closedWait = requireNotNull(deadline.beginLoading(closed, foreground = true))
        deadline.close()
        assertFalse(deadline.expire(closedWait))
    }

    @Test
    fun staleGenerationOrSessionDeadlineCannotFailReplacementContent() {
        val deadline = TouchMediaRenderDeadline()
        val old = TouchMediaRenderDeadline.LoadToken(loadGeneration = 5, sessionGeneration = 3)
        val replacement = TouchMediaRenderDeadline.LoadToken(loadGeneration = 6, sessionGeneration = 4)

        val oldWait = requireNotNull(deadline.beginLoading(old, foreground = true))
        val replacementWait = requireNotNull(deadline.beginLoading(replacement, foreground = true))
        assertFalse(deadline.expire(oldWait))
        assertTrue(deadline.expire(replacementWait))
    }

    @Test
    fun productionViewArmsAndFencesTheForegroundRenderDeadline() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()

        assertTrue(source.contains("private var renderDeadlineTask: Runnable? = null"))
        assertTrue(source.contains("val load = TouchMediaRenderDeadline.LoadToken(++loadGeneration, sessionGeneration)"))
        assertTrue(source.contains("armRenderDeadline(renderDeadline.beginLoading(load, active))"))
        assertTrue(source.contains("armRenderDeadline(renderDeadline.foregrounded())"))
        assertTrue(source.contains("renderDeadline.hidden()"))
        assertTrue(source.contains("postDelayed(task, RENDER_DEADLINE_MILLIS)"))
        assertTrue(source.contains("if (!renderDeadline.expire(token)) return@Runnable"))
        assertTrue(source.contains("token.load.sessionGeneration == sessionGeneration"))
        assertTrue(source.contains("context.getString(R.string.touch_media_render_timeout)"))
        assertTrue(source.contains("renderDeadline.close()\n        cancelRenderDeadlineTask()"))
        assertTrue(source.contains("renderDeadline.failed()\n        cancelRenderDeadlineTask()\n        loadState.markContentProcessClosed"))
    }

    @Test
    fun retryWithoutAConfiguredUrlDoesNothingAndOriginPolicyRejectsEscape() {
        assertNull(TouchMediaLoadState().retry())
        val policy = FrameUrlPolicy()
        assertTrue(policy.isAllowedTopLevelNavigation("https://photos.example/frame", "https://photos.example/next"))
        assertFalse(policy.isAllowedTopLevelNavigation("https://photos.example/frame", "https://elsewhere.example/next"))
        assertFalse(policy.isSafeConfigurationUrl("file:///tmp/frame"))
    }
}
