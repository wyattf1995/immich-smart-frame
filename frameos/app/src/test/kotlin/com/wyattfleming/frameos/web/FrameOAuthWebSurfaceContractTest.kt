package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrameOAuthWebSurfaceContractTest {
    private val source = File(
        "src/main/kotlin/com/wyattfleming/frameos/web/FrameOAuthWebSurface.kt",
    ).readText()

    @Test
    fun usesSameRuntimeTextureBackendWithBoundedFailureAndClosePaths() {
        assertTrue(source.contains("setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)"))
        assertTrue(source.contains("const val TIMEOUT_MILLIS = 120_000L"))
        assertTrue(source.contains("const val MAX_TIMEOUT_MILLIS = 120_000L"))
        assertTrue(source.contains("OAuth authorization URL must stay on the configured HTTPS origin"))
        assertTrue(source.contains("callbackPageUrl: String"))
        assertTrue(source.contains("FrameOAuthNavigationPolicy(configuredHomeAssistantUrl, callbackPageUrl)"))
        assertTrue(source.contains("navigationPolicy.decide(authorizationUrl) == FrameOAuthNavigationPolicy.Decision.ALLOW"))
        assertTrue(source.contains("fun start()"))
        assertTrue(source.contains("if (started || closed) return"))
        assertTrue(source.indexOf("fun start()") < source.indexOf("oauthSession.open(runtime)"))
        assertTrue(source.contains("FrameOAuthNavigationPolicy.Decision.CALLBACK"))
        assertTrue(source.contains("GeckoResult.fromValue(AllowOrDeny.DENY)"))
        assertTrue(source.contains("if (session != null) runCatching(::releaseSession)"))
        assertTrue(source.contains("runCatching { oauthSession.setFocused(false) }"))
        assertTrue(source.contains("runCatching { oauthSession.setActive(false) }"))
        assertTrue(source.contains("runCatching { oauthSession.stop() }"))
        assertTrue(source.contains("runCatching { oauthSession.close() }"))
        assertTrue(source.contains("override fun onNewSession("))
        assertTrue(source.contains("): GeckoResult<GeckoSession>? = null"))
        assertTrue(source.contains("override fun onLoadError("))
        assertTrue(source.contains("error: WebRequestError"))
        assertFalse(source.contains("startActivity("))
        assertFalse(source.contains("DownloadDelegate"))
    }
}
