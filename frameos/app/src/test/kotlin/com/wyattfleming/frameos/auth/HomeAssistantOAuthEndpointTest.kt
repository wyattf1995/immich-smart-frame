package com.wyattfleming.frameos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeAssistantOAuthEndpointTest {
    private val endpoint = HomeAssistantOAuthEndpoint.fromDisplayUrl(
        "https://home.example.invalid:8123/local/frameos.html",
    )
    private val callback = "https://home.example.invalid:8123/local/frameos-oauth.html"

    @Test
    fun `uses same-origin authorize and token URLs with the callback page as client ID`() {
        val state = OAuthState.fromCryptographicBytes(ByteArray(32) { it.toByte() })
        val authorization = endpoint.authorizationRequest(
            callbackPageUrl = callback,
            state = state,
            codeChallenge = "challenge-value",
        )

        assertEquals("https://home.example.invalid:8123/auth/token", endpoint.tokenUrl())
        assertEquals(state, authorization.state)
        assertEquals(
            "https://home.example.invalid:8123/auth/authorize?response_type=code&client_id=https%3A%2F%2Fhome.example.invalid%3A8123%2Flocal%2Fframeos-oauth.html&redirect_uri=https%3A%2F%2Fhome.example.invalid%3A8123%2Flocal%2Fframeos-oauth.html&state=${state.value}&code_challenge=challenge-value&code_challenge_method=S256",
            authorization.url,
        )
        assertEquals(
            "grant_type=authorization_code&code=authorization-code&client_id=https%3A%2F%2Fhome.example.invalid%3A8123%2Flocal%2Fframeos-oauth.html&redirect_uri=https%3A%2F%2Fhome.example.invalid%3A8123%2Flocal%2Fframeos-oauth.html&code_verifier=verifier-value",
            endpoint.tokenRequestBody(callback, "authorization-code", "verifier-value"),
        )
    }

    @Test
    fun `rejects HTTP cross-origin and invalid callback pages`() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantOAuthEndpoint.fromDisplayUrl("http://home.example.invalid:8123/local/frameos.html")
        }
        listOf(
            "http://home.example.invalid:8123/local/frameos-oauth.html",
            "https://photos.example.invalid/frameos-oauth.html",
            "https://home.example.invalid:8123/local/frameos-oauth.html?code=already-present",
            "not a URL",
        ).forEach { invalidCallback ->
            assertThrows(IllegalArgumentException::class.java) {
                endpoint.authorizationRequest(
                    invalidCallback,
                    OAuthState.fromCryptographicBytes(ByteArray(32) { 7 }),
                    "challenge-value",
                )
            }
        }
    }

    @Test
    fun `parses token responses without a string representation that logs values`() {
        val tokens = endpoint.parseTokenResponse(
            """{"access_token":"access-secret","refresh_token":"refresh-secret","token_type":"Bearer","expires_in":1800}""",
        )

        assertEquals("access-secret", tokens.accessToken)
        assertEquals("refresh-secret", tokens.refreshToken)
        assertEquals("Bearer", tokens.tokenType)
        assertEquals(1800L, tokens.expiresInSeconds)
        assertFalse(tokens.toString().contains("access-secret"))
        assertFalse(tokens.toString().contains("refresh-secret"))
    }
}
