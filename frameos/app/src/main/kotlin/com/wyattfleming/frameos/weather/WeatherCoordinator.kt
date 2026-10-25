package com.wyattfleming.frameos.weather

fun interface WeatherAccessTokenProvider {
    fun validAccessToken(): String?
}

interface DeadlineAwareWeatherAccessTokenProvider : WeatherAccessTokenProvider {
    fun validAccessToken(deadline: WeatherRequestDeadline): String?
}

data class WeatherDisplayLoad(val load: WeatherLoadResult, val accessToken: String?)

class WeatherCoordinator(
    private val accessTokenProvider: WeatherAccessTokenProvider,
    private val repository: WeatherRepository,
    private val presenter: WeatherPresenter,
    private val entityId: String,
    private val refreshTimeoutMillis: Long = TOTAL_REFRESH_TIMEOUT_MILLIS,
    private val tokenRefreshBudgetMillis: Long = TOKEN_REFRESH_BUDGET_MILLIS,
) {
    init {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(entityId)
    }

    fun refresh(): WeatherPresentation {
        val deadline = WeatherRequestDeadline(timeoutMillis = refreshTimeoutMillis)
        val token = validAccessToken(deadline).orEmpty()
        return presenter.present(repository.refresh(entityId, token, deadline))
    }

    fun cachedPresentation(): WeatherPresentation? = repository.cached(entityId)?.let(presenter::present)

    /**
     * For screens that draw the forecast themselves (the shared Weather page): the load itself, and the token that
     * fetched it so a related read (sunrise and sunset) can reuse it within the same refresh.
     */
    fun refreshForDisplay(): WeatherDisplayLoad {
        val deadline = WeatherRequestDeadline(timeoutMillis = refreshTimeoutMillis)
        val token = validAccessToken(deadline).orEmpty()
        return WeatherDisplayLoad(repository.refresh(entityId, token, deadline), token.ifBlank { null })
    }

    fun cachedLoad(): WeatherLoadResult? = repository.cached(entityId)

    fun cancel() = repository.cancel()

    private fun validAccessToken(deadline: WeatherRequestDeadline): String? {
        val provider = accessTokenProvider
        if (provider !is DeadlineAwareWeatherAccessTokenProvider) return provider.validAccessToken()
        val budget = deadline.remainingMillis().coerceAtMost(tokenRefreshBudgetMillis)
        if (budget == 0L) return null
        return provider.validAccessToken(WeatherRequestDeadline(timeoutMillis = budget))
    }

    private companion object {
        const val TOTAL_REFRESH_TIMEOUT_MILLIS = 15_000L
        const val TOKEN_REFRESH_BUDGET_MILLIS = 4_000L
    }
}
