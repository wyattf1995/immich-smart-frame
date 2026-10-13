package com.wyattfleming.frameos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthCallbackVerifierTest {
    private val callbackPageUrl = "https://home.example.invalid:8444/local/frameos-oauth.html"

    @Test
    fun `accepts the exact native callback once after matching the cryptographic state`() {
        val store = RecordingPendingStateStore("state-secret")
        val verifier = OAuthCallbackVerifier(store, callbackPageUrl)

        val result = verifier.verify("frameos://oauth/callback?code=one-time-code&state=state-secret")

        assertEquals("one-time-code", result?.code)
        assertFalse(result.toString().contains("one-time-code"))
        assertNull(verifier.verify("frameos://oauth/callback?code=replay&state=state-secret"))
    }

    @Test
    fun `accepts the exact intercepted HTTPS callback once after matching state`() {
        val store = RecordingPendingStateStore("state-secret")
        val verifier = OAuthCallbackVerifier(store, callbackPageUrl)

        val result = verifier.verify(
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=one-time-code&state=state-secret",
        )

        assertEquals("one-time-code", result?.code)
        assertFalse(result.toString().contains("one-time-code"))
        assertNull(verifier.verify(
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=replay&state=state-secret",
        ))
    }

    @Test
    fun `consumes and rejects mismatched native state without exposing callback secrets`() {
        val store = RecordingPendingStateStore("expected-state")
        val verifier = OAuthCallbackVerifier(store, callbackPageUrl)

        assertNull(verifier.verify("frameos://oauth/callback?code=secret-code&state=wrong-state"))
        assertNull(store.consume())
        assertFalse(verifier.toString().contains("secret-code"))
    }

    @Test
    fun `consumes and rejects mismatched HTTPS state and explicit OAuth errors`() {
        val wrongState = RecordingPendingStateStore("expected-state")
        assertNull(OAuthCallbackVerifier(wrongState, callbackPageUrl).verify(
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=secret-code&state=wrong-state",
        ))
        assertNull(wrongState.consume())

        val explicitError = RecordingPendingStateStore("expected-state")
        assertNull(OAuthCallbackVerifier(explicitError, callbackPageUrl).verify(
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=secret-code&state=expected-state&error=access_denied",
        ))
        assertNull(explicitError.consume())
    }

    @Test
    fun `rejects lookalike native and HTTPS callbacks fragments and duplicate values`() {
        listOf(
            "https://oauth/callback?code=x&state=s",
            "frameos://attacker/callback?code=x&state=s",
            "frameos://oauth/other?code=x&state=s",
            "frameos://oauth/callback?code=x&code=y&state=s",
            "frameos://oauth/callback?code=x&state=s#fragment",
            "https://attacker.example.invalid:8444/local/frameos-oauth.html?code=x&state=s",
            "https://home.example.invalid:8444/local/other.html?code=x&state=s",
            "https://user@home.example.invalid:8444/local/frameos-oauth.html?code=x&state=s",
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=x&state=s#fragment",
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=x&code=y&state=s",
        ).forEach { callback ->
            assertNull(OAuthCallbackVerifier(RecordingPendingStateStore("s"), callbackPageUrl).verify(callback))
        }
    }

    private class RecordingPendingStateStore(private var state: String?) : PendingOAuthStateStore {
        override fun write(state: OAuthState) { this.state = state.value }
        override fun consume(): String? = state.also { state = null }
    }
}
