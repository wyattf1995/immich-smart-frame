package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class WeatherCoordinatorDeadlineTest {
    @Test
    fun `refresh budgets token renewal separately from the weather fetch deadline`() {
        val remote = RecordingDeadlineRemote(snapshot)
        val tokenProvider = RecordingDeadlineAwareTokenProvider("renewed-access")
        val coordinator = WeatherCoordinator(
            accessTokenProvider = tokenProvider,
            repository = WeatherRepository(
                remote = remote,
                cache = EmptyCache,
                clock = { 1_000L },
                freshForMillis = 60_000L,
            ),
            presenter = WeatherPresenter(ZoneId.of("America/Los_Angeles"), Locale.US),
            entityId = "weather.home",
            refreshTimeoutMillis = 200L,
            tokenRefreshBudgetMillis = 80L,
        )

        val presentation = coordinator.refresh()

        assertEquals("renewed-access", remote.token)
        assertEquals("89°F", presentation.temperature)
        assertNotNull(tokenProvider.seenBudgetMillis)
        assertTrue(tokenProvider.seenBudgetMillis!! in 1L..80L)
        assertNotNull(remote.seenDeadlineMillis)
        assertTrue(
            "expected token work to consume part of the 200ms total budget: ${remote.seenDeadlineMillis}",
            remote.seenDeadlineMillis!! in 1L..140L,
        )
    }

    @Test
    fun `deadline aware token failure still presents stale weather without touching the network`() {
        val remote = RecordingDeadlineRemote(snapshot)
        val coordinator = WeatherCoordinator(
            accessTokenProvider = RecordingDeadlineAwareTokenProvider(token = null),
            repository = WeatherRepository(
                remote = remote,
                cache = RecordingCache(
                    CachedWeatherSnapshot(
                        snapshot = snapshot,
                        savedAtEpochMillis = 1_000L,
                    ),
                ),
                clock = { 2_000L },
                freshForMillis = 100L,
            ),
            presenter = WeatherPresenter(ZoneId.of("America/Los_Angeles"), Locale.US),
            entityId = "weather.home",
        )

        val presentation = coordinator.refresh()

        assertEquals("89°F", presentation.temperature)
        assertTrue(!presentation.authenticationRequired)
        assertTrue(presentation.status.contains("Offline"))
        assertEquals(0, remote.calls)
    }

    private class RecordingDeadlineAwareTokenProvider(
        private val token: String?,
    ) : DeadlineAwareWeatherAccessTokenProvider {
        var seenBudgetMillis: Long? = null

        override fun validAccessToken(): String? = error("deadline-aware overload should be used")

        override fun validAccessToken(deadline: WeatherRequestDeadline): String? {
            seenBudgetMillis = deadline.remainingMillis()
            Thread.sleep(60L)
            return token
        }
    }

    private class RecordingDeadlineRemote(
        private val snapshot: WeatherSnapshot,
    ) : DeadlineAwareWeatherRemote {
        var calls = 0
        var token: String? = null
        var seenDeadlineMillis: Long? = null

        override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult =
            error("deadline-aware overload should be used")

        override fun fetch(
            entityId: String,
            bearerToken: String,
            deadline: WeatherRequestDeadline,
        ): WeatherRemoteResult {
            calls += 1
            token = bearerToken
            seenDeadlineMillis = deadline.remainingMillis()
            return WeatherRemoteResult.Success(snapshot)
        }
    }

    private class RecordingCache(
        private val cached: CachedWeatherSnapshot?,
    ) : WeatherCache {
        override fun read(key: WeatherCacheKey): CachedWeatherSnapshot? = cached
        override fun write(key: WeatherCacheKey, snapshot: WeatherSnapshot, savedAtEpochMillis: Long) = Unit
        override fun recordError(message: String) = Unit
        override fun clear() = Unit
    }

    private data object EmptyCache : WeatherCache {
        override fun read(key: WeatherCacheKey): CachedWeatherSnapshot? = null
        override fun write(key: WeatherCacheKey, snapshot: WeatherSnapshot, savedAtEpochMillis: Long) = Unit
        override fun recordError(message: String) = Unit
        override fun clear() = Unit
    }

    private companion object {
        val snapshot = WeatherSnapshot(
            current = WeatherCurrent(
                entityId = "weather.home",
                condition = WeatherCondition.SUNNY,
                displayCondition = "Sunny",
                temperature = 89.0,
                temperatureUnit = "°F",
                humidity = 20.0,
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
    }
}
