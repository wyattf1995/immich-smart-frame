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
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one", "calendar.two", "calendar.three"), emptyList())
        val transport = HomeAssistantHttpTransport { request: HomeAssistantHttpRequest ->
            when { request.url.contains("calendar.one") || request.url.contains("calendar.two") -> HomeAssistantHttpResponse(200, """[{"summary":"Dinner","start":{"dateTime":"2026-10-12T18:00:00Z"},"end":{"dateTime":"2026-10-12T19:00:00Z"}}]""")
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
    @Test fun `one transport exception remains a partial calendar result`() {
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one", "calendar.two"), emptyList())
        val transport = HomeAssistantHttpTransport { request ->
            if (request.url.contains("calendar.one")) HomeAssistantHttpResponse(200, "[]") else throw java.io.IOException("offline")
        }
        TouchCalendarRepository(config, TouchCalendarTokenProvider { "token" }, transport, ZoneId.of("UTC")).use { repository ->
            val loaded = repository.load(TouchCalendarRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)))
            assertEquals(TouchCalendarConnection.CONNECTED, loaded.connection)
            assertTrue(loaded.partial)
        }
    }

    @Test fun `invalidate fences a stale range before it can publish`() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one"), emptyList())
        val transport = HomeAssistantHttpTransport { _ -> started.countDown(); release.await(); HomeAssistantHttpResponse(200, "[]") }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            TouchCalendarRepository(config, TouchCalendarTokenProvider { "token" }, transport, ZoneId.of("UTC")).use { repository ->
                val future = worker.submit<TouchCalendarLoad> { repository.load(TouchCalendarRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19))) }
                assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS))
                repository.invalidate()
                release.countDown()
                assertEquals(TouchCalendarConnection.OFFLINE, future.get(1, java.util.concurrent.TimeUnit.SECONDS).connection)
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun `closed repository rejects a range before reading a token`() {
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one"), emptyList())
        val repository = TouchCalendarRepository(config, TouchCalendarTokenProvider { throw AssertionError("token must not be read after close") }, HomeAssistantHttpTransport { throw AssertionError("transport must not run") }, ZoneId.of("UTC"))
        repository.close()
        assertEquals(TouchCalendarConnection.OFFLINE, repository.load(TouchCalendarRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19))).connection)
    }

}
