package com.wyattfleming.frameos.weather

fun interface WeatherAccessTokenProvider {
    fun validAccessToken(): String?
}

class WeatherCoordinator(
    private val accessTokenProvider: WeatherAccessTokenProvider,
    private val repository: WeatherRepository,
    private val presenter: WeatherPresenter,
    private val entityId: String,
) {
    init {
        HomeAssistantWeatherEndpoint.requireWeatherEntity(entityId)
    }

    fun refresh(): WeatherPresentation {
        val token = accessTokenProvider.validAccessToken().orEmpty()
        return presenter.present(repository.refresh(entityId, token))
    }

    fun cachedPresentation(): WeatherPresentation? = repository.cached(entityId)?.let(presenter::present)

    fun cancel() = repository.cancel()
}
