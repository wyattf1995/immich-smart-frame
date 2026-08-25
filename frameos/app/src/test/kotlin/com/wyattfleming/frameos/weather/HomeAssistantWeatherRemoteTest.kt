package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantWeatherRemoteTest {
    private val endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl(
        "https://home.example.invalid:8123/local/frameos.html",
    )
    private val token = "secret-token-value"

    @Test
    fun `gets current conditions then posts daily and hourly return_response forecasts`() {
        val transport = RecordingTransport(
            HomeAssistantHttpResponse(200, currentPayload),
            HomeAssistantHttpResponse(200, dailyEnvelope),
            HomeAssistantHttpResponse(200, hourlyEnvelope),
        )
        val remote = HomeAssistantWeatherRemote(endpoint, transport, HomeAssistantWeatherParser())

        val result = remote.fetch("weather.home", token)

        val success = assertType<WeatherRemoteResult.Success>(result)
        assertEquals(89.0, success.snapshot.current.temperature, 0.0)
        assertEquals(1, success.snapshot.daily.size)
        assertEquals(1, success.snapshot.hourly.size)
        assertEquals("GET", transport.requests[0].method)
        assertEquals("https://home.example.invalid:8123/api/states/weather.home", transport.requests[0].url)
        assertEquals("Bearer $token", transport.requests[0].headers["Authorization"])
        assertEquals("POST", transport.requests[1].method)
        assertEquals(
            "https://home.example.invalid:8123/api/services/weather/get_forecasts?return_response",
            transport.requests[1].url,
        )
        assertEquals("{\"entity_id\":\"weather.home\",\"type\":\"daily\"}", transport.requests[1].body)
        assertEquals("POST", transport.requests[2].method)
        assertEquals(
            "{\"entity_id\":\"weather.home\",\"type\":\"hourly\"}",
            transport.requests[2].body,
        )
    }

    @Test
    fun `maps HTTP auth failures to AuthRequired without exposing response or token`() {
        listOf(401, 403).forEach { status ->
            val remote = HomeAssistantWeatherRemote(
                endpoint,
                RecordingTransport(HomeAssistantHttpResponse(status, "Bearer $token was rejected")),
                HomeAssistantWeatherParser(),
            )

            val result = assertType<WeatherRemoteResult.AuthRequired>(remote.fetch("weather.home", token))

            assertFalse(result.reason.contains(token))
            assertFalse(result.reason.contains("rejected"))
        }
    }

    @Test
    fun `maps network parse and other HTTP failures to Offline without response or token leakage`() {
        val cases = listOf(
            RecordingTransport(throws = IllegalStateException("socket failure $token")),
            RecordingTransport(HomeAssistantHttpResponse(500, "server body $token")),
            RecordingTransport(HomeAssistantHttpResponse(200, "not json")),
        )

        cases.forEach { transport ->
            val result = HomeAssistantWeatherRemote(endpoint, transport, HomeAssistantWeatherParser())
                .fetch("weather.home", token)
            val offline = assertType<WeatherRemoteResult.Offline>(result)
            assertEquals("weather_offline", offline.reason)
            assertFalse(offline.reason.contains(token))
        }
    }

    private class RecordingTransport(
        vararg responses: HomeAssistantHttpResponse,
        private val throws: Throwable? = null,
    ) : HomeAssistantHttpTransport {
        val requests = mutableListOf<HomeAssistantHttpRequest>()
        private val queue = ArrayDeque(responses.asList())

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            requests += request
            throws?.let { throw it }
            return queue.removeFirst()
        }
    }

    private inline fun <reified T> assertType(value: Any): T {
        assertTrue("Expected ${T::class.simpleName}, got ${value::class.simpleName}", value is T)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private companion object {
        val currentPayload = """
            {"entity_id":"weather.home","state":"sunny","attributes":{"temperature":89,"temperature_unit":"°F"}}
        """.trimIndent()
        val dailyEnvelope = """
            {"service_response":{"weather.home":{"forecast":[{"datetime":"2026-08-24T00:00:00-07:00","condition":"sunny","temperature":102,"templow":78}]}}}
        """.trimIndent()
        val hourlyEnvelope = """
            {"service_response":{"weather.home":{"forecast":[{"datetime":"2026-08-24T15:00:00-07:00","condition":"sunny","temperature":89}]}}}
        """.trimIndent()
    }
}
