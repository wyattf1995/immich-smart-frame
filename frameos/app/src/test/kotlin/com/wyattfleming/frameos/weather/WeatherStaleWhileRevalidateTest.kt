package com.wyattfleming.frameos.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class WeatherStaleWhileRevalidateTest {
    @Test
    fun `presents cached weather before independently refreshing the remote snapshot`() {
        val cached = snapshot(70.0)
        val latest = snapshot(89.0)
        val remote = RecordingRemote(latest)
        val coordinator = WeatherCoordinator(
            accessTokenProvider = WeatherAccessTokenProvider { "access" },
            repository = WeatherRepository(
                remote = remote,
                cache = Cache(CachedWeatherSnapshot(cached, 1_000L)),
                clock = { 2_000L },
                freshForMillis = 60_000L,
            ),
            presenter = WeatherPresenter(ZoneId.of("America/Los_Angeles"), Locale.US),
            entityId = "weather.home",
        )

        assertEquals("70°F", coordinator.cachedPresentation()!!.temperature)
        assertEquals(0, remote.calls)
        assertEquals("89°F", coordinator.refresh().temperature)
        assertEquals(1, remote.calls)
    }

    private class RecordingRemote(private val latest: WeatherSnapshot) : WeatherRemote {
        var calls = 0
        override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult {
            calls += 1
            return WeatherRemoteResult.Success(latest)
        }
    }

    private class Cache(private val cached: CachedWeatherSnapshot) : WeatherCache {
        override fun read(entityId: String): CachedWeatherSnapshot = cached
        override fun write(entityId: String, snapshot: WeatherSnapshot, savedAtEpochMillis: Long) = Unit
        override fun recordError(message: String) = Unit
    }

    private fun snapshot(temperature: Double) = WeatherSnapshot(
        current = WeatherCurrent(
            entityId = "weather.home", condition = WeatherCondition.SUNNY, displayCondition = "Sunny",
            temperature = temperature, temperatureUnit = "°F", humidity = null, windSpeed = null,
            windSpeedUnit = null, windBearing = null, pressure = null, visibility = null,
            visibilityUnit = null, updatedAtEpochMillis = 1_000L,
        ),
        daily = emptyList(), hourly = emptyList(), alert = null,
    )
}
