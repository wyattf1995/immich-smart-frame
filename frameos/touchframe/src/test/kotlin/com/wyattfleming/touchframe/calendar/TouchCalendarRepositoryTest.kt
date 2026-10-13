package com.wyattfleming.touchframe.calendar

import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.touchframe.data.TouchConfiguration
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.io.InterruptedIOException
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    @Test fun `invalidate retains stale request handles until serialized cleanup`() {
        val transport = BlockingCancellableTransport()
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one"), emptyList())
        val worker = Executors.newSingleThreadExecutor()
        try {
            TouchCalendarRepository(config, TouchCalendarTokenProvider { "token" }, transport, ZoneId.of("UTC")).use { repository ->
                val oldRange = TouchCalendarRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19))
                val nextRange = TouchCalendarRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26))
                val stale = worker.submit<TouchCalendarLoad> { repository.load(oldRange) }
                assertTrue("stale request did not start", transport.firstStarted.await(1, TimeUnit.SECONDS))

                val startedAt = System.nanoTime()
                repository.invalidate()
                assertTrue("invalidate waited for a socket", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 250)

                val next = worker.submit<TouchCalendarLoad> { repository.load(nextRange) }
                assertTrue("next request did not begin", transport.secondStarted.await(1, TimeUnit.SECONDS))
                assertEquals(TouchCalendarConnection.OFFLINE, stale.get(1, TimeUnit.SECONDS).connection)
                assertEquals(TouchCalendarConnection.CONNECTED, next.get(1, TimeUnit.SECONDS).connection)
                assertTrue("a stale socket reached the next request", transport.secondStartedAfterCancellation.get())
                assertEquals(1, transport.cancelCalls.get())
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun `closed repository rejects a range before reading a token`() {
        val config = TouchConfiguration("https://photos.example", "https://ha.example", "https://ha.example", "weather.home", null, "climate.home", listOf("calendar.one"), emptyList())
        val repository = TouchCalendarRepository(config, TouchCalendarTokenProvider { throw AssertionError("token must not be read after close") }, HomeAssistantHttpTransport { throw AssertionError("transport must not run") }, ZoneId.of("UTC"))
        repository.close()
        assertEquals(TouchCalendarConnection.OFFLINE, repository.load(TouchCalendarRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19))).connection)
    }


    private class BlockingCancellableTransport : CancellableHomeAssistantHttpTransport {
        val firstStarted = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val cancelCalls = AtomicInteger()
        val secondStartedAfterCancellation = AtomicBoolean(false)
        private val cancelled = CountDownLatch(1)

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            if (request.url.contains("start=2026-10-12")) {
                firstStarted.countDown()
                while (cancelCalls.get() == 0) {
                    try {
                        cancelled.await(25, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        // This transport deliberately ignores Future interruption until its socket is closed.
                    }
                }
                throw InterruptedIOException("stale request disconnected")
            }
            secondStartedAfterCancellation.set(cancelCalls.get() > 0)
            secondStarted.countDown()
            if (!secondStartedAfterCancellation.get()) throw AssertionError("next request began before stale socket cleanup")
            return HomeAssistantHttpResponse(200, "[]")
        }

        override fun cancelInFlight() {
            cancelCalls.incrementAndGet()
            cancelled.countDown()
        }
    }
}
