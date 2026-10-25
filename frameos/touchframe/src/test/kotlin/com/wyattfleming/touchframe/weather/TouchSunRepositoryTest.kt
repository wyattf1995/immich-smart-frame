package com.wyattfleming.touchframe.weather

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime

class TouchSunRepositoryTest {
    private class FakeHomeAssistant(private val responses: Map<String, Pair<Int, String>>) : HomeAssistantHttpTransport {
        val paths = mutableListOf<String>()

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            val path = request.url.removePrefix(ORIGIN)
            paths += path
            val (status, body) = responses[path] ?: (404 to "")
            return HomeAssistantHttpResponse(status, body)
        }
    }

    private fun millis(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun load(transport: FakeHomeAssistant) = TouchSunRepository(ORIGIN, transport).load("token", WeatherRequestDeadline(timeoutMillis = 4_000L))

    @Test
    fun `sunrise and sunset come from sun dot sun in one read`() {
        val transport = FakeHomeAssistant(mapOf("/api/states/sun.sun" to (200 to """{"entity_id":"sun.sun","state":"above_horizon",
            "attributes":{"next_rising":"2026-10-16T13:56:12.123456+00:00","next_setting":"2026-10-16T01:17:40+00:00"}}""")))

        assertEquals(TouchSunTimes(millis("2026-10-16T13:56:12.123456+00:00"), millis("2026-10-16T01:17:40+00:00")), load(transport))
        assertEquals(listOf("/api/states/sun.sun"), transport.paths)
    }

    @Test
    fun `without sun dot sun's attributes the timestamp sensors stand in`() {
        val transport = FakeHomeAssistant(mapOf(
            "/api/states/sun.sun" to (200 to """{"entity_id":"sun.sun","state":"above_horizon","attributes":{"friendly_name":"Sun"}}"""),
            "/api/states/sensor.sun_next_rising" to (200 to """{"entity_id":"sensor.sun_next_rising","state":"2026-10-16T13:56:12+00:00"}"""),
            "/api/states/sensor.sun_next_setting" to (200 to """{"entity_id":"sensor.sun_next_setting","state":"unavailable"}"""),
        ))

        assertEquals(TouchSunTimes(millis("2026-10-16T13:56:12+00:00"), null), load(transport))
    }

    @Test
    fun `no sun anywhere is simply no sun times`() {
        assertNull(load(FakeHomeAssistant(emptyMap())))
        assertNull(TouchSunParser.fromSun("not json"))
    }

    private companion object {
        const val ORIGIN = "https://ha.example.test"
    }
}
