package com.wyattfleming.touchframe.home

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.touchframe.data.TouchConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class TouchHomeRepositoryTest {
    private val config = TouchConfiguration.from(
        "https://photos.example",
        "https://ha.example",
        "weather.home",
        null,
        calendarEntityIds = listOf("calendar.family", "calendar.work"),
    )!!

    @Test
    fun `refresh makes only configured state and calendar requests`() {
        val transport = FakeTransport()
        TouchHomeRepository(config, { "token" }, transport, clock = { 100L }, zoneId = ZoneOffset.UTC).use { repository ->
            val model = repository.refresh(null)
            assertEquals(TouchConnection.CONNECTED, model.connection)
            assertEquals(4, transport.requests.size)
            assertEquals(2, transport.requests.count { it.url.contains("/api/states/") })
            assertEquals(2, transport.requests.count { it.url.contains("/api/calendars/") })
            assertTrue(transport.requests.all { it.timeoutMillis in 1..12_000 })
        }
    }

    @Test
    fun `failed refresh preserves prior cards and marks offline`() {
        val previous = TouchHomeModel("Sunny", "72°F", emptyList(), null, TouchConnection.CONNECTED, 5L)
        val transport = FakeTransport(status = 500)
        TouchHomeRepository(config, { "token" }, transport).use { repository ->
            val model = repository.refresh(previous)
            assertEquals("Sunny", model.weatherText)
            assertEquals(5L, model.lastUpdatedEpochMillis)
            assertEquals(TouchConnection.OFFLINE, model.connection)
        }
    }

    @Test
    fun `authentication failure preserves prior cards and requests sign in`() {
        val previous = TouchHomeModel("Sunny", "72°F", emptyList(), null, TouchConnection.CONNECTED, 5L)
        TouchHomeRepository(config, { "token" }, FakeTransport(status = 401)).use { repository ->
            assertEquals(TouchConnection.SIGN_IN_REQUIRED, repository.refresh(previous).connection)
        }
    }

    private class FakeTransport(private val status: Int = 200) : HomeAssistantHttpTransport {
        val requests = CopyOnWriteArrayList<HomeAssistantHttpRequest>()
        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            requests += request
            if (status != 200) return HomeAssistantHttpResponse(status, "{}")
            return when {
                request.url.contains("weather.home") -> HomeAssistantHttpResponse(200, """{"state":"sunny","attributes":{"temperature":72,"temperature_unit":"°F"}}""")
                request.url.contains("climate.entryway_entryway") -> HomeAssistantHttpResponse(200, """{"state":"heat","attributes":{"current_temperature":70,"temperature":71,"temperature_unit":"°F","hvac_modes":["off","heat"],"min_temp":50,"max_temp":85,"target_temp_step":1}}""")
                else -> HomeAssistantHttpResponse(200, "[]")
            }
        }
    }
}
