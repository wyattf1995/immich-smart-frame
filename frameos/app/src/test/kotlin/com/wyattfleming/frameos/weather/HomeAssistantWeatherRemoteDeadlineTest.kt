package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class HomeAssistantWeatherRemoteDeadlineTest {
    private val endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl(
        "https://home.example.invalid:8123/local/frameos.html",
    )

    @Test
    fun `primary requests reserve fallback budget and fallback never forwards the bearer token`() {
        val fallbackEndpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl(
            "https://fallback.example.invalid:9443/local/frameos.html",
        )
        val transport = RecordingTransport { request ->
            when {
                request.url.startsWith("https://home.example.invalid:8123/") ->
                    HomeAssistantHttpResponse(500, "primary unavailable")
                request.method == "GET" -> HomeAssistantHttpResponse(200, currentPayload)
                request.body == "{\"entity_id\":\"weather.home\",\"type\":\"daily\"}" ->
                    HomeAssistantHttpResponse(200, dailyEnvelope)
                request.body == "{\"entity_id\":\"weather.home\",\"type\":\"hourly\"}" ->
                    HomeAssistantHttpResponse(200, hourlyEnvelope)
                else -> error("Unexpected fallback request: $request")
            }
        }
        val remote = HomeAssistantWeatherRemote(
            endpoint = endpoint,
            transport = transport,
            parser = HomeAssistantWeatherParser(),
            fallbackEndpoint = fallbackEndpoint,
        )

        val result = remote.fetch(
            entityId = "weather.home",
            bearerToken = "primary-secret",
            deadline = WeatherRequestDeadline(startNanos = System.nanoTime(), timeoutMillis = 6_000L),
        )

        assertTrue(result is WeatherRemoteResult.Success)
        val requests = transport.requests.toList()
        val primaryRequests = requests.filter { it.url.startsWith("https://home.example.invalid:8123/") }
        val fallbackRequests = requests.filter { it.url.startsWith("https://fallback.example.invalid:9443/") }
        assertEquals(3, primaryRequests.size)
        assertEquals(3, fallbackRequests.size)
        assertTrue(primaryRequests.all { it.headers["Authorization"] == "Bearer primary-secret" })
        assertTrue(fallbackRequests.all { "Authorization" !in it.headers })
        assertTrue(primaryRequests.all { request -> request.timeoutMillis?.let { it in 1L..2_000L } == true })
        assertTrue(fallbackRequests.all { request -> request.timeoutMillis?.let { it > 2_000L } == true })
    }

    private class RecordingTransport(
        private val responseForRequest: (HomeAssistantHttpRequest) -> HomeAssistantHttpResponse,
    ) : HomeAssistantHttpTransport {
        val requests = Collections.synchronizedList(mutableListOf<HomeAssistantHttpRequest>())

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            requests += request
            return responseForRequest(request)
        }
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
