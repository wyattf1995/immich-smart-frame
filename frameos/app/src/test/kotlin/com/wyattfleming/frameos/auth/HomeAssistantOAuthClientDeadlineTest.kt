package com.wyattfleming.frameos.auth

import com.wyattfleming.frameos.FrameOperationLogEntry
import com.wyattfleming.frameos.FrameOperationLogger
import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class HomeAssistantOAuthClientDeadlineTest {
    private val endpoint = HomeAssistantOAuthEndpoint.fromDisplayUrl(
        "https://home.example.invalid:8123/local/frameos.html",
    )
    private val callback = "https://home.example.invalid:8123/local/frameos-oauth.html"

    @Test
    fun `expired session forwards the remaining deadline and logs timeout classification with elapsed time`() {
        val store = RecordingSessionStore(
            OAuthSession("expired-access", "refresh-secret", expiresAtEpochMillis = 20_000L, authEpoch = "account-a"),
        )
        val logger = RecordingLogger()
        val transport = ThrowingTransport(SocketTimeoutException("slow refresh"))
        val clockReadings = longArrayOf(1_000_000L, 2_000_000L, 5_000_000L)
        var clockIndex = 0
        val client = HomeAssistantOAuthClient(
            endpoint = endpoint,
            callbackPageUrl = callback,
            transport = transport,
            store = store,
            clock = { 30_000L },
            operationLogger = logger,
            clockNanos = { clockReadings.getOrElse(clockIndex++) { clockReadings.last() } },
        )

        val token = client.validAccessToken(
            WeatherRequestDeadline(startNanos = System.nanoTime(), timeoutMillis = 250L),
        )

        assertNull(token)
        assertEquals(1, transport.requests.size)
        assertTrue(transport.requests.single().timeoutMillis in 1L..250L)
        assertEquals(
            FrameOperationLogEntry(
                route = "oauth",
                stage = "token_refresh",
                outcome = "timeout",
                elapsedMillis = 4L,
            ),
            logger.entries.single(),
        )
        assertEquals("expired-access", store.read()?.accessToken)
    }

    private class RecordingLogger : FrameOperationLogger {
        val entries = mutableListOf<FrameOperationLogEntry>()

        override fun log(entry: FrameOperationLogEntry) {
            entries += entry
        }
    }

    private class ThrowingTransport(
        private val throwable: Throwable,
    ) : HomeAssistantHttpTransport {
        val requests = mutableListOf<HomeAssistantHttpRequest>()

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            requests += request
            throw throwable
        }
    }

    private class RecordingSessionStore(
        private var current: OAuthSession? = null,
    ) : OAuthSessionStore {
        override fun read(): OAuthSession? = current

        override fun write(session: OAuthSession) {
            current = session
        }

        override fun clear() {
            current = null
        }
    }
}
