package com.wyattfleming.touchframe.calendar

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.touchframe.data.TouchConfiguration
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TouchCalendarRepositoryTest {
    @Test fun `deduplicates calendar events and reports partial response failure`() {
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one", "calendar.two"), emptyList())
        val transport = HomeAssistantHttpTransport { request: HomeAssistantHttpRequest ->
            when { request.url.contains("calendar.one") -> HomeAssistantHttpResponse(200, """[{"summary":"Dinner","start":{"dateTime":"2026-10-12T18:00:00Z"},"end":{"dateTime":"2026-10-12T19:00:00Z"}}]""")
                else -> HomeAssistantHttpResponse(500, "") }
        }
        TouchCalendarRepository(config, TouchCalendarTokenProvider { "token" }, transport, ZoneId.of("UTC")).use { repository ->
            val loaded = repository.load(TouchCalendarRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)))
            assertEquals(TouchCalendarConnection.CONNECTED, loaded.connection)
            assertTrue(loaded.partial)
            assertEquals(listOf("Dinner"), loaded.events.map { it.title })
        }
    }
    @Test fun `range request is bounded and asks for selected period`() {
        val requests = mutableListOf<HomeAssistantHttpRequest>()
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one"), emptyList())
        val transport = HomeAssistantHttpTransport { request -> requests += request; HomeAssistantHttpResponse(200, "[]") }
        TouchCalendarRepository(config, TouchCalendarTokenProvider { "token" }, transport, ZoneId.of("UTC")).use { repository ->
            repository.load(TouchCalendarRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 8)))
        }
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.contains("start=2026-10-01"))
        assertTrue(requests.single().url.contains("end=2026-10-08"))
    }
}
