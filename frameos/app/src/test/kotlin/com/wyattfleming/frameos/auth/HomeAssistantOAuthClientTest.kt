package com.wyattfleming.frameos.auth

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantOAuthClientTest {
    private val endpoint = HomeAssistantOAuthEndpoint.fromDisplayUrl(
        "https://home.example.invalid:8123/local/frameos.html",
    )
    private val callback = "https://home.example.invalid:8123/local/frameos-oauth.html"

    @Test
    fun `exchanges one time code and reuses a still valid access token`() {
        val store = RecordingSessionStore()
        val transport = QueueTransport(
            HomeAssistantHttpResponse(
                200,
                """{"access_token":"access-secret","refresh_token":"refresh-secret","token_type":"Bearer","expires_in":1800}""",
            ),
        )
        val client = HomeAssistantOAuthClient(endpoint, callback, transport, store, clock = { 10_000L })

        assertTrue(client.exchangeAuthorizationCode("one-time-code"))
        assertEquals("access-secret", client.validAccessToken())
        assertEquals(1, transport.requests.size)
        assertEquals("POST", transport.requests.single().method)
        assertEquals("https://home.example.invalid:8123/auth/token", transport.requests.single().url)
        assertEquals("application/x-www-form-urlencoded", transport.requests.single().headers["Content-Type"])
        assertEquals(
            "grant_type=authorization_code&code=one-time-code&client_id=https%3A%2F%2Fhome.example.invalid%3A8123%2Flocal%2Fframeos-oauth.html",
            transport.requests.single().body,
        )
        assertFalse(store.saved.toString().contains("access-secret"))
        assertFalse(store.saved.toString().contains("refresh-secret"))
    }

    @Test
    fun `refreshes an expired session and keeps the existing refresh token`() {
        val store = RecordingSessionStore(
            OAuthSession("expired-access", "refresh-secret", expiresAtEpochMillis = 20_000L),
        )
        val transport = QueueTransport(
            HomeAssistantHttpResponse(
                200,
                """{"access_token":"new-access","token_type":"Bearer","expires_in":1800}""",
            ),
        )
        val client = HomeAssistantOAuthClient(endpoint, callback, transport, store, clock = { 30_000L })

        assertEquals("new-access", client.validAccessToken())
        assertEquals("refresh-secret", store.saved?.refreshToken)
        assertEquals(
            "grant_type=refresh_token&refresh_token=refresh-secret&client_id=https%3A%2F%2Fhome.example.invalid%3A8123%2Flocal%2Fframeos-oauth.html",
            transport.requests.single().body,
        )
    }

    @Test
    fun `returns no token on missing session or failed exchange without logging response secrets`() {
        val missing = HomeAssistantOAuthClient(endpoint, callback, QueueTransport(), RecordingSessionStore(), clock = { 0L })
        assertNull(missing.validAccessToken())

        val failedStore = RecordingSessionStore()
        val failed = HomeAssistantOAuthClient(
            endpoint,
            callback,
            QueueTransport(HomeAssistantHttpResponse(400, "refresh-secret should not escape")),
            failedStore,
            clock = { 0L },
        )
        assertFalse(failed.exchangeAuthorizationCode("one-time-code"))
        assertNull(failedStore.saved)
    }

    private class RecordingSessionStore(
        private var current: OAuthSession? = null,
    ) : OAuthSessionStore {
        var saved: OAuthSession? = current

        override fun read(): OAuthSession? = current

        override fun write(session: OAuthSession) {
            current = session
            saved = session
        }

        override fun clear() {
            current = null
            saved = null
        }
    }

    private class QueueTransport(vararg responses: HomeAssistantHttpResponse) : HomeAssistantHttpTransport {
        val requests = mutableListOf<HomeAssistantHttpRequest>()
        private val queue = ArrayDeque(responses.asList())

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            requests += request
            return queue.removeFirst()
        }
    }
}
