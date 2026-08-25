package com.wyattfleming.frameos.weather

class WeatherRepository(
    private val remote: WeatherRemote,
    private val cache: WeatherCache,
    private val clock: () -> Long,
    private val freshForMillis: Long,
) {
    init {
        require(freshForMillis >= 0L)
    }

    fun load(entityId: String, bearerToken: String): WeatherLoadResult {
        (cached(entityId) as? WeatherLoadResult.Fresh)?.let { return it }
        return refresh(entityId, bearerToken)
    }

    fun cached(entityId: String): WeatherLoadResult? {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(entityId)
        val now = clock()
        val cached = cache.read(entityId)
        if (cached != null && now - cached.savedAtEpochMillis <= freshForMillis) {
            return WeatherLoadResult.Fresh(cached.snapshot)
        }
        return cached?.let { WeatherLoadResult.Stale(it.snapshot) }
    }

    fun refresh(entityId: String, bearerToken: String): WeatherLoadResult {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(entityId)
        val now = clock()
        val cached = cache.read(entityId)
        if (bearerToken.isBlank()) {
            cache.recordError(ERROR_AUTH_REQUIRED)
            return cached?.let { WeatherLoadResult.Stale(it.snapshot) } ?: WeatherLoadResult.AuthRequired
        }

        return when (val result = remote.fetch(entityId, bearerToken)) {
            is WeatherRemoteResult.Success -> {
                cache.write(entityId, result.snapshot, now)
                WeatherLoadResult.Fresh(result.snapshot)
            }
            is WeatherRemoteResult.Offline -> {
                cache.recordError(ERROR_OFFLINE)
                cached?.let { WeatherLoadResult.Stale(it.snapshot) } ?: WeatherLoadResult.Offline
            }
            is WeatherRemoteResult.AuthRequired -> {
                cache.recordError(ERROR_AUTH_REQUIRED)
                WeatherLoadResult.AuthRequired
            }
        }
    }

    fun cancel() = remote.cancel()

    private companion object {
        const val ERROR_OFFLINE = "weather_offline"
        const val ERROR_AUTH_REQUIRED = "weather_auth_required"
    }
}
