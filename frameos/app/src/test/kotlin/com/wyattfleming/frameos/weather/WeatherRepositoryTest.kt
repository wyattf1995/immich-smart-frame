package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class WeatherRepositoryTest {
    private val snapshot = WeatherSnapshot(
        current = WeatherCurrent(
            entityId = "weather.home",
            condition = WeatherCondition.SUNNY,
            displayCondition = "Sunny",
            temperature = 89.0,
            temperatureUnit = "°F",
            humidity = null,
            windSpeed = null,
            windSpeedUnit = null,
            windBearing = null,
            pressure = null,
            visibility = null,
            visibilityUnit = null,
            updatedAtEpochMillis = 1_000L,
        ),
        daily = emptyList(),
        hourly = emptyList(),
        alert = null,
    )

    @Test
    fun `returns a fresh remote snapshot and stores a token free cache entry`() {
        val cache = RecordingWeatherCache()
        val repository = WeatherRepository(
            remote = FakeWeatherRemote(WeatherRemoteResult.Success(snapshot)),
            cache = cache,
            clock = { 1_500L },
            freshForMillis = 60_000L,
        )

        val result = repository.load("weather.home", "secret-token-value")

        assertEquals(WeatherLoadResult.Fresh(snapshot), result)
        assertSame(snapshot, cache.saved)
        assertFalse(cache.serializedPayload.contains("secret-token-value"))
        assertFalse(cache.lastErrorMessage.contains("secret-token-value"))
    }

    @Test
    fun `uses a fresh cache before calling the network`() {
        val cache = RecordingWeatherCache(cached = CachedWeatherSnapshot(snapshot, savedAtEpochMillis = 1_000L))
        val remote = FakeWeatherRemote(WeatherRemoteResult.Success(snapshot.copy()))
        val repository = WeatherRepository(remote, cache, clock = { 1_500L }, freshForMillis = 60_000L)

        assertEquals(WeatherLoadResult.Fresh(snapshot), repository.load("weather.home", "secret-token-value"))
        assertEquals(0, remote.calls)
    }

    @Test
    fun `uses stale cached weather when the network is offline`() {
        val cache = RecordingWeatherCache(cached = CachedWeatherSnapshot(snapshot, savedAtEpochMillis = 1_000L))
        val repository = WeatherRepository(
            FakeWeatherRemote(WeatherRemoteResult.Offline("network unavailable")),
            cache,
            clock = { 122_000L },
            freshForMillis = 60_000L,
        )

        assertEquals(WeatherLoadResult.Stale(snapshot), repository.load("weather.home", "secret-token-value"))
    }

    @Test
    fun `keeps stale cached weather visible when session renewal is unavailable`() {
        val cache = RecordingWeatherCache(cached = CachedWeatherSnapshot(snapshot, savedAtEpochMillis = 1_000L))
        val remote = FakeWeatherRemote(WeatherRemoteResult.Success(snapshot.copy()))
        val repository = WeatherRepository(
            remote,
            cache,
            clock = { 122_000L },
            freshForMillis = 60_000L,
        )

        assertEquals(WeatherLoadResult.Stale(snapshot), repository.load("weather.home", ""))
        assertEquals(0, remote.calls)
        assertEquals("weather_auth_required", cache.lastErrorMessage)
    }

    @Test
    fun `keeps stale cached weather visible while a rejected credential needs reauthentication`() {
        val cache = RecordingWeatherCache(cached = CachedWeatherSnapshot(snapshot, savedAtEpochMillis = 1_000L))
        val repository = WeatherRepository(
            remote = FakeWeatherRemote(WeatherRemoteResult.AuthRequired("credential rejected")),
            cache = cache,
            clock = { 122_000L },
            freshForMillis = 60_000L,
        )

        assertEquals(
            WeatherLoadResult.Stale(snapshot, authenticationRequired = true),
            repository.refresh("weather.home", "access-token"),
        )
        assertEquals("weather_auth_required", cache.lastErrorMessage)
    }

    @Test
    fun `expires stale weather after its bounded fallback lifetime`() {
        val repository = WeatherRepository(
            remote = FakeWeatherRemote(WeatherRemoteResult.Offline("network unavailable")),
            cache = RecordingWeatherCache(cached = CachedWeatherSnapshot(snapshot, savedAtEpochMillis = 1_000L)),
            clock = { 62_000L },
            freshForMillis = 1_000L,
            maxStaleMillis = 60_000L,
        )

        assertEquals(WeatherLoadResult.Offline, repository.refresh("weather.home", "access-token"))
    }

    @Test
    fun `reports offline without a cache and auth required without leaking the token`() {
        val offlineCache = RecordingWeatherCache()
        val offline = WeatherRepository(
            FakeWeatherRemote(WeatherRemoteResult.Offline("network unavailable")),
            offlineCache,
            clock = { 1_000L },
            freshForMillis = 60_000L,
        )
        assertEquals(WeatherLoadResult.Offline, offline.load("weather.home", "secret-token-value"))

        val authCache = RecordingWeatherCache()
        val auth = WeatherRepository(
            FakeWeatherRemote(WeatherRemoteResult.AuthRequired("Bearer secret-token-value rejected")),
            authCache,
            clock = { 1_000L },
            freshForMillis = 60_000L,
        )
        assertEquals(WeatherLoadResult.AuthRequired, auth.load("weather.home", "secret-token-value"))
        assertFalse(authCache.serializedPayload.contains("secret-token-value"))
        assertFalse(authCache.lastErrorMessage.contains("secret-token-value"))
    }

    @Test
    fun `scopes every cache access by origin entity and authentication epoch`() {
        val cache = RecordingWeatherCache()
        val repository = WeatherRepository(
            remote = FakeWeatherRemote(WeatherRemoteResult.Success(snapshot)),
            cache = cache,
            clock = { 1_500L },
            freshForMillis = 60_000L,
            homeAssistantOrigin = "https://home.example.invalid:8123/",
            authEpochProvider = { "account-b" },
        )

        repository.refresh("weather.home", "secret-token-value")

        assertEquals(
            WeatherCacheKey("https://home.example.invalid:8123", "weather.home", "account-b"),
            cache.lastKey,
        )
    }

    private class RecordingWeatherCache(
        private val cached: CachedWeatherSnapshot? = null,
    ) : WeatherCache {
        var saved: WeatherSnapshot? = null
        var serializedPayload: String = ""
        var lastErrorMessage: String = ""

        var lastKey: WeatherCacheKey? = null

        override fun read(key: WeatherCacheKey): CachedWeatherSnapshot? {
            lastKey = key
            return cached
        }

        override fun write(key: WeatherCacheKey, snapshot: WeatherSnapshot, savedAtEpochMillis: Long) {
            lastKey = key
            saved = snapshot
            serializedPayload = snapshot.toString()
        }

        override fun recordError(message: String) {
            lastErrorMessage = message
        }

        override fun clear() = Unit
    }

    private class FakeWeatherRemote(
        private val result: WeatherRemoteResult,
    ) : WeatherRemote {
        var calls: Int = 0

        override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult {
            calls += 1
            return result
        }
    }
}
