package com.wyattfleming.touchframe.home

import com.wyattfleming.frameos.weather.HomeAssistantHttpRequest
import com.wyattfleming.frameos.weather.HomeAssistantHttpResponse
import com.wyattfleming.frameos.weather.HomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import com.wyattfleming.touchframe.data.TouchConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        TouchHomeRepository(config, { _ -> "token" }, transport, clock = { 100L }, zoneId = ZoneOffset.UTC).use { repository ->
            val model = repository.refresh(null)
            assertEquals(TouchConnection.CONNECTED, model.connection)
            assertEquals(4, transport.requests.size)
            assertEquals(2, transport.requests.count { it.url.contains("/api/states/") })
            assertEquals(2, transport.requests.count { it.url.contains("/api/calendars/") })
            assertTrue(transport.requests.all { request ->
                request.timeoutMillis?.let { it in 1L..12_000L } == true
            })
        }
    }

    @Test
    fun `refresh globally orders configured calendar events before the cap`() {
        val transport = FakeTransport(calendarBodies = mapOf(
            "calendar.family" to """[{"summary":"Later","start":{"dateTime":"2026-10-15T12:00:00Z"}}]""",
            "calendar.work" to """[{"summary":"Sooner","start":{"dateTime":"2026-10-13T12:00:00Z"}}]""",
        ))
        TouchHomeRepository(config, { _ -> "token" }, transport, clock = { 1_760_000_000_000L }, zoneId = ZoneOffset.UTC).use { repository ->
            assertEquals(listOf("Sooner", "Later"), repository.refresh(null).events.map { it.title })
        }
    }

    @Test
    fun `cancellation during token acquisition prevents every HTTP request`() {
        val tokenEntered = CountDownLatch(1)
        val releaseToken = CountDownLatch(1)
        val transport = FakeTransport()
        val caller = Executors.newSingleThreadExecutor()
        try {
            TouchHomeRepository(config, { _ ->
                tokenEntered.countDown()
                releaseToken.await(2, TimeUnit.SECONDS)
                "token"
            }, transport).use { repository ->
                val future = caller.submit<TouchHomeModel> { repository.refresh(null) }
                assertTrue(tokenEntered.await(2, TimeUnit.SECONDS))
                repository.cancel()
                releaseToken.countDown()
                assertEquals(TouchConnection.OFFLINE, future.get(2, TimeUnit.SECONDS).connection)
                assertTrue(transport.requests.isEmpty())
            }
        } finally {
            releaseToken.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun `failed refresh preserves prior cards and marks offline`() {
        val previous = TouchHomeModel("Sunny", "72°F", emptyList(), null, TouchConnection.CONNECTED, 5L)
        val transport = FakeTransport(status = 500)
        TouchHomeRepository(config, { _ -> "token" }, transport).use { repository ->
            val model = repository.refresh(previous)
            assertEquals("Sunny", model.weatherText)
            assertEquals(5L, model.lastUpdatedEpochMillis)
            assertEquals(TouchConnection.OFFLINE, model.connection)
        }
    }

    @Test
    fun `expired total deadline performs no requests and preserves prior cards`() {
        val previous = TouchHomeModel("Sunny", "72°F", emptyList(), null, TouchConnection.CONNECTED, 5L)
        val transport = FakeTransport()
        val expired = { _: Long -> WeatherRequestDeadline(startNanos = 0L, timeoutMillis = 1L, clockNanos = { 1_000_000L }) }
        TouchHomeRepository(config, { _ -> "token" }, transport, deadlineFactory = expired).use { repository ->
            val result = repository.refresh(previous)
            assertEquals(TouchConnection.OFFLINE, result.connection)
            assertEquals("Sunny", result.weatherText)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun `cancel releases bounded in-flight requests and preserves prior cards`() {
        val transport = BlockingTransport()
        val previous = TouchHomeModel("Sunny", "72°F", emptyList(), null, TouchConnection.CONNECTED, 5L)
        val caller = Executors.newSingleThreadExecutor()
        try {
            TouchHomeRepository(config, { _ -> "token" }, transport).use { repository ->
                val future = caller.submit<TouchHomeModel> { repository.refresh(previous) }
                assertTrue(transport.started.await(2, TimeUnit.SECONDS))
                repository.cancel()
                val result = future.get(2, TimeUnit.SECONDS)
                assertEquals(TouchConnection.OFFLINE, result.connection)
                assertEquals("Sunny", result.weatherText)
                assertTrue(transport.cancelled)
            }
        } finally {
            caller.shutdownNow()
        }
    }

    @Test
    fun `authentication failure preserves prior cards and requests sign in`() {
        val previous = TouchHomeModel("Sunny", "72°F", emptyList(), null, TouchConnection.CONNECTED, 5L)
        TouchHomeRepository(config, { _ -> "token" }, FakeTransport(status = 401)).use { repository ->
            assertEquals(TouchConnection.SIGN_IN_REQUIRED, repository.refresh(previous).connection)
        }
    }

    @Test
    fun `failed refresh makes previously confirmed climate read-only`() {
        // This case asserts the authorization gate, not concurrent request completion.
        // A single test-owned executor plus a barrier drains any cancelled GET before
        // the request-count snapshot; production keeps its bounded two-worker executor.
        val requestExecutor = Executors.newSingleThreadExecutor()
        val healthy = FakeTransport()
        TouchHomeRepository(config, { _ -> "token" }, healthy, requestExecutor = requestExecutor).use { repository ->
            val connected = repository.refresh(null)
            assertEquals(TouchConnection.CONNECTED, connected.connection)
            healthy.status = 500
            assertEquals(TouchConnection.OFFLINE, repository.refresh(connected).connection)
            requestExecutor.submit {}.get(2, TimeUnit.SECONDS)
            val requestCountAfterFailedRefresh = healthy.requests.size
            assertTrue(!repository.changeClimateTarget(73.0))
            assertEquals(requestCountAfterFailedRefresh, healthy.requests.size)
        }
    }

    @Test
    fun `optional server step still yields a connected climate card`() {
        val transport = FakeTransport(climateBody = """{"entity_id":"climate.entryway_entryway","state":"heat","attributes":{"current_temperature":70,"temperature":71,"temperature_unit":"°F","supported_features":1,"hvac_modes":["heat"],"min_temp":50,"max_temp":85}}""")
        TouchHomeRepository(config, { _ -> "token" }, transport).use { repository ->
            val model = repository.refresh(null)
            assertEquals(TouchConnection.CONNECTED, model.connection)
            assertEquals(null, model.climate?.step)
            assertTrue(repository.changeClimateTarget(72.25))
        }
    }

    @Test
    fun `old HA climate unit falls back to config once and retains target guard`() {
        val transport = FakeTransport(climateBody = """{"entity_id":"climate.entryway_entryway","state":"heat","attributes":{"current_temperature":70,"temperature":71,"supported_features":1,"hvac_modes":["heat"],"min_temp":50,"max_temp":85}}""")
        TouchHomeRepository(config, { _ -> "token" }, transport).use { repository ->
            val first = repository.refresh(null)
            val second = repository.refresh(first)

            assertEquals(TouchConnection.CONNECTED, first.connection)
            assertEquals("°F", first.climate?.unit)
            assertTrue(first.climate?.canSetTarget == true)
            assertEquals(TouchConnection.CONNECTED, second.connection)
            assertEquals(1, transport.requests.count { it.url.endsWith("/api/config") })
        }
    }

    @Test
    fun `range-only climate never posts a single target command`() {
        val transport = FakeTransport(climateBody = """{"entity_id":"climate.entryway_entryway","state":"heat_cool","attributes":{"current_temperature":70,"target_temp_low":68,"target_temp_high":75,"temperature_unit":"°F","supported_features":2,"hvac_modes":["heat_cool"],"min_temp":50,"max_temp":85}}""")
        TouchHomeRepository(config, { _ -> "token" }, transport).use { repository ->
            val model = repository.refresh(null)
            val reads = transport.requests.size

            assertEquals(TouchConnection.CONNECTED, model.connection)
            assertTrue(model.climate?.canSetTarget == false)
            assertTrue(!repository.changeClimateTarget(72.0))
            assertEquals(reads, transport.requests.size)
        }
    }

    @Test
    fun `observed off thermostat stays connected and cannot post a made up target`() {
        val transport = FakeTransport(climateBody = """{"entity_id":"climate.entryway_entryway","state":"off","attributes":{"min_temp":50,"max_temp":90,"current_temperature":71,"temperature":null,"target_temp_low":null,"target_temp_high":null,"hvac_modes":["heat","cool","heat_cool","off"],"supported_features":411,"hvac_action":"off"}}""")
        TouchHomeRepository(config, { _ -> "token" }, transport).use { repository ->
            val model = repository.refresh(null)
            val reads = transport.requests.size

            assertEquals(TouchConnection.CONNECTED, model.connection)
            assertEquals("°F", model.climate?.unit)
            assertEquals(null, model.climate?.targetTemperature)
            assertTrue(model.climate?.canSetTarget == false)
            assertTrue(model.climate?.canSetMode == true)
            assertTrue(!repository.changeClimateTarget(72.0))
            assertEquals(reads, transport.requests.size)
        }
    }

    private class BlockingTransport : CancellableHomeAssistantHttpTransport {
        val started = CountDownLatch(2)
        private val released = CountDownLatch(1)
        @Volatile var cancelled = false

        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            started.countDown()
            released.await(2, TimeUnit.SECONDS)
            if (cancelled) throw java.io.InterruptedIOException("cancelled")
            return HomeAssistantHttpResponse(200, "{}")
        }

        override fun cancelInFlight() {
            cancelled = true
            released.countDown()
        }
    }

    private class FakeTransport(
        var status: Int = 200,
        private val calendarBodies: Map<String, String> = emptyMap(),
        private val climateBody: String = """{"entity_id":"climate.entryway_entryway","state":"heat","attributes":{"current_temperature":70,"temperature":71,"temperature_unit":"°F","supported_features":1,"hvac_modes":["off","heat"],"min_temp":50,"max_temp":85,"target_temp_step":1}}""",
    ) : HomeAssistantHttpTransport {
        val requests = CopyOnWriteArrayList<HomeAssistantHttpRequest>()
        override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
            requests += request
            if (status != 200) return HomeAssistantHttpResponse(status, "{}")
            return when {
                request.url.contains("weather.home") -> HomeAssistantHttpResponse(200, """{"state":"sunny","attributes":{"temperature":72,"temperature_unit":"°F"}}""")
                request.url.contains("climate.entryway_entryway") -> HomeAssistantHttpResponse(200, climateBody)
                request.url.endsWith("/api/config") -> HomeAssistantHttpResponse(200, """{"unit_system":{"temperature":"°F"}}""")
                else -> HomeAssistantHttpResponse(200, calendarBodies.entries.firstOrNull { request.url.contains("/api/calendars/${it.key}") }?.value ?: "[]")
            }
        }
    }
}
