package com.wyattfleming.frameos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthCallbackVerifierTest {
    @Test
    fun `accepts the exact app callback once after matching the cryptographic state`() {
        val store = RecordingPendingStateStore("state-secret")
        val verifier = OAuthCallbackVerifier(store)

        val result = verifier.verify(
            "frameos://oauth/callback?code=one-time-code&state=state-secret",
        )

        assertEquals("one-time-code", result?.code)
        assertFalse(result.toString().contains("one-time-code"))
        assertNull(verifier.verify("frameos://oauth/callback?code=replay&state=state-secret"))
    }

    @Test
    fun `consumes and rejects mismatched state without exposing callback secrets`() {
        val store = RecordingPendingStateStore("expected-state")
        val verifier = OAuthCallbackVerifier(store)

        assertNull(verifier.verify("frameos://oauth/callback?code=secret-code&state=wrong-state"))
        assertNull(store.consume())
        assertFalse(verifier.toString().contains("secret-code"))
    }

    @Test
    fun `rejects lookalike schemes hosts paths fragments and duplicate values`() {
        listOf(
            "https://oauth/callback?code=x&state=s",
            "frameos://attacker/callback?code=x&state=s",
            "frameos://oauth/other?code=x&state=s",
            "frameos://oauth/callback?code=x&code=y&state=s",
            "frameos://oauth/callback?code=x&state=s#fragment",
        ).forEach { callback ->
            assertNull(OAuthCallbackVerifier(RecordingPendingStateStore("s")).verify(callback))
        }
    }

    private class RecordingPendingStateStore(
        private var state: String?,
    ) : PendingOAuthStateStore {
        override fun write(state: OAuthState) {
            this.state = state.value
        }

        override fun consume(): String? = state.also { state = null }
    }
}
