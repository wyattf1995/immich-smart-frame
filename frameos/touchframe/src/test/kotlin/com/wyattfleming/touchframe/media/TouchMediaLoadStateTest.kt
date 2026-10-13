package com.wyattfleming.touchframe.media

import com.wyattfleming.frameos.security.FrameUrlPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchMediaLoadStateTest {
    @Test
    fun sameConfiguredUrlDoesNotRequestAnotherLoadButChangedUrlDoes() {
        val state = TouchMediaLoadState()

        assertEquals(TouchMediaLoadState.Decision.LOAD, state.showUrl("https://photos.example/frame"))
        assertEquals(TouchMediaLoadState.Decision.ALREADY_LOADED, state.showUrl("https://photos.example/frame"))
        assertEquals(TouchMediaLoadState.Decision.LOAD, state.showUrl("https://photos.example/art"))
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
        val source = File("src/main/kotlin/com/wyattfleming/touchframe/media/TouchMediaView.kt").readText()

        assertTrue(source.contains("if (!success) reportFailure(session, context.getString(R.string.touch_media_load_failed, label))"))
        assertTrue(source.contains("override fun onFirstContentfulPaint(session: GeckoSession) = reportReady(session)"))
        assertFalse(source.contains("if (success) reportReady(session)"))
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
