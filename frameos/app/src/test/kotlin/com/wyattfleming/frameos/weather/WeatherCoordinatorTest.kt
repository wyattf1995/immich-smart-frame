package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class WeatherCoordinatorTest {
    @Test
    fun `loads and presents live weather with the renewable access token`() {
        val remote = RecordingRemote(snapshot)
        val coordinator = WeatherCoordinator(
            accessTokenProvider = WeatherAccessTokenProvider { "renewed-access" },
            repository = WeatherRepository(
                remote = remote,
                cache = EmptyCache,
                clock = { 1_000L },
                freshForMillis = 60_000L,
            ),
            presenter = WeatherPresenter(ZoneId.of("America/Los_Angeles"), Locale.US),
            entityId = "weather.home",
        )

        val presentation = coordinator.refresh()

        assertEquals("renewed-access", remote.token)
        assertEquals("89°F", presentation.temperature)
        assertEquals("Sunny", presentation.condition)
    }

    @Test
    fun `presents sign in without touching the network when no session exists`() {
        val remote = RecordingRemote(snapshot)
        val coordinator = WeatherCoordinator(
            accessTokenProvider = WeatherAccessTokenProvider { null },
            repository = WeatherRepository(
                remote = remote,
                cache = EmptyCache,
                clock = { 1_000L },
                freshForMillis = 60_000L,
            ),
            presenter = WeatherPresenter(ZoneId.of("America/Los_Angeles"), Locale.US),
            entityId = "weather.home",
        )

        val presentation = coordinator.refresh()

        assertEquals("Weather needs Home Assistant sign-in", presentation.emptyMessage)
        assertEquals(0, remote.calls)
    }

    private class RecordingRemote(
        private val snapshot: WeatherSnapshot,
    ) : WeatherRemote {
        var calls = 0
        var token: String? = null

        override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult {
            calls += 1
            token = bearerToken
            return WeatherRemoteResult.Success(snapshot)
        }
    }

    private data object EmptyCache : WeatherCache {
        override fun read(entityId: String): CachedWeatherSnapshot? = null
        override fun write(entityId: String, snapshot: WeatherSnapshot, savedAtEpochMillis: Long) = Unit
        override fun recordError(message: String) = Unit
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
