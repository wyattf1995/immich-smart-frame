package com.wyattfleming.frameos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityEmbeddedOAuthContractTest {
    private val source = File("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt").readText()

    @Test
    fun embeddedCallbackUsesAuthoritativeCallbackHandler() {
        val begin = methodBody("private fun beginWeatherAuthorization()")

        assertContainsInOrder(
            begin,
            listOf(
                "onCallback = { callback ->",
                "closeWeatherAuthorization(consumeState = false)",
                "handleOAuthCallbackUrl(callback)",
                "oauthSurface = surface",
                "oauthOverlay = overlay",
                "overlay.addView(surface",
                "root.addView(overlay",
                "surface.requestFocus()",
                "surface.start()",
            ),
        )
    }

    @Test
    fun initializesEndpointAwareVerifierBeforeEmbeddedAuthorization() {
        val initialization = methodBody("private fun initializeWeatherRuntime()")
        assertContainsInOrder(
            initialization,
            listOf(
                "val callbackPageUrl = callbackPageUrl(activeConfiguration.homeAssistantUrl)",
                "callbackVerifier = OAuthCallbackVerifier(pendingStateStore, callbackPageUrl)",
            ),
        )
    }

    @Test
    fun callbackVerifierRunsBeforePendingAuthorizationCodeIsAssigned() {
        val callback = methodBody("private fun handleOAuthCallbackUrl(callbackUrl: String)")

        assertContainsInOrder(
            callback,
            listOf(
                "val verifier = callbackVerifier",
                "val code = verifier.verify(callbackUrl)",
                "pendingAuthorizationPreviousAuthEpoch = oauthClient?.authEpoch()",
                "pendingAuthorizationCode = code.code",
                "startAuthorizationExchange()",
            ),
        )
    }

    @Test
    fun authorizationCancellationOnlyConsumesAnActiveChallengeAndLifecycleClosesIt() {
        val close = methodBody("private fun closeWeatherAuthorization(consumeState: Boolean)")
        val onPause = methodBody("override fun onPause()")
        val onDestroy = methodBody("override fun onDestroy()")

        assertContainsInOrder(
            close,
            listOf(
                "val hadAuthorization = hadSurface || embeddedAuthorizationPending",
                "embeddedAuthorizationPending = false",
                "oauthSurface?.close()",
                "if (consumeState && hadAuthorization)",
                "pendingOAuthStateStore?.consume()",
            ),
        )
        assertTrue(onPause.contains("closeWeatherAuthorization(consumeState = true)"))
        assertTrue(onDestroy.contains("closeWeatherAuthorization(consumeState = true)"))
    }

    private fun methodBody(signature: String): String {
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

    private fun assertContainsInOrder(body: String, snippets: List<String>) {
        var offset = 0
        snippets.forEach { snippet ->
            val found = body.indexOf(snippet, offset)
            assertTrue("Missing in order: $snippet", found >= 0)
            offset = found + snippet.length
        }
    }
}
