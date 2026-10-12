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
        assertTrue(source.contains("navigationPolicy.decide(authorizationUrl) == FrameOAuthNavigationPolicy.Decision.ALLOW"))
        assertTrue(source.contains("FrameOAuthNavigationPolicy.Decision.CALLBACK"))
        assertTrue(source.contains("GeckoResult.fromValue(AllowOrDeny.DENY)"))
        assertTrue(source.contains("if (session != null) runCatching(::releaseSession)"))
        assertTrue(source.contains("oauthSession.setActive(false)"))
        assertTrue(source.contains("oauthSession.close()"))
        assertFalse(source.contains("startActivity("))
        assertFalse(source.contains("DownloadDelegate"))
    }
}
