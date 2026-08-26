package com.wyattfleming.frameos.weather

class WeatherRepository(
    private val remote: WeatherRemote,
    private val cache: WeatherCache,
    private val clock: () -> Long,
    private val freshForMillis: Long,
    private val maxStaleMillis: Long = MAX_STALE_MILLIS,
    private val homeAssistantOrigin: String = DEFAULT_ORIGIN,
    private val authEpochProvider: () -> String = { LEGACY_AUTH_EPOCH },
) {
    init {
        require(freshForMillis >= 0L)
        require(maxStaleMillis >= freshForMillis)
    }

    fun load(entityId: String, bearerToken: String): WeatherLoadResult {
        (cached(entityId) as? WeatherLoadResult.Fresh)?.let { return it }
        return refresh(entityId, bearerToken)
    }

    fun cached(entityId: String): WeatherLoadResult? {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(entityId)
        val now = clock()
        val key = cacheKey(entityId)
        val cached = cache.read(key)
        if (cached != null && now - cached.savedAtEpochMillis <= freshForMillis) {
            return WeatherLoadResult.Fresh(cached.snapshot)
        }
        return cached.staleIfUsable(now)
    }

    fun refresh(entityId: String, bearerToken: String): WeatherLoadResult {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(entityId)
        val now = clock()
        val key = cacheKey(entityId)
        val cached = cache.read(key)
        if (bearerToken.isBlank()) {
            cache.recordError(ERROR_AUTH_REQUIRED)
            return cached.staleIfUsable(now) ?: WeatherLoadResult.AuthRequired
        }

        return when (val result = remote.fetch(entityId, bearerToken)) {
            is WeatherRemoteResult.Success -> {
                cache.write(key, result.snapshot, now)
                WeatherLoadResult.Fresh(result.snapshot)
            }
            is WeatherRemoteResult.Offline -> {
                cache.recordError(ERROR_OFFLINE)
                cached.staleIfUsable(now) ?: WeatherLoadResult.Offline
            }
            is WeatherRemoteResult.AuthRequired -> {
                cache.recordError(ERROR_AUTH_REQUIRED)
                cached.staleIfUsable(now, authenticationRequired = true)
                    ?: WeatherLoadResult.AuthRequired
            }
        }
    }

    fun cancel() = remote.cancel()

    private companion object {
        const val MAX_STALE_MILLIS = 24 * 60 * 60 * 1_000L
        const val ERROR_OFFLINE = "weather_offline"
        const val ERROR_AUTH_REQUIRED = "weather_auth_required"
        const val DEFAULT_ORIGIN = "unknown"
        const val LEGACY_AUTH_EPOCH = "legacy"
    }

    private fun cacheKey(entityId: String): WeatherCacheKey = WeatherCacheKey(
        homeAssistantOrigin = homeAssistantOrigin.removeSuffix("/"),
        entityId = entityId,
        authEpoch = authEpochProvider().ifBlank { LEGACY_AUTH_EPOCH },
    )

    private fun CachedWeatherSnapshot?.staleIfUsable(
        now: Long,
        authenticationRequired: Boolean = false,
    ): WeatherLoadResult.Stale? {
        val cached = this ?: return null
        val age = now - cached.savedAtEpochMillis
        if (age !in 0..maxStaleMillis) return null
        return WeatherLoadResult.Stale(cached.snapshot, authenticationRequired)
    }
}
