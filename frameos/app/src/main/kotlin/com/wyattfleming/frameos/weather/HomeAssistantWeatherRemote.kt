package com.wyattfleming.frameos.weather

data class HomeAssistantHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

data class HomeAssistantHttpResponse(
    val statusCode: Int,
    val body: String,
)

fun interface HomeAssistantHttpTransport {
    fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse
}

class HomeAssistantWeatherRemote(
    private val endpoint: HomeAssistantWeatherEndpoint,
    private val transport: HomeAssistantHttpTransport,
    private val parser: HomeAssistantWeatherParser,
) : WeatherRemote {
    override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult {
        if (bearerToken.isBlank()) return WeatherRemoteResult.AuthRequired(AUTH_REQUIRED)
        return try {
            val headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $bearerToken",
            )
            val currentResponse = execute(
                HomeAssistantHttpRequest(
                    method = "GET",
                    url = endpoint.stateUrl(entityId),
                    headers = headers,
                ),
            )
            val current = parser.parseCurrentState(entityId, currentResponse.body)
            val daily = fetchForecast(entityId, WeatherForecastType.DAILY, headers)
            val hourly = fetchForecast(entityId, WeatherForecastType.HOURLY, headers)
            WeatherRemoteResult.Success(
                WeatherSnapshot(
                    current = current,
                    daily = daily,
                    hourly = hourly,
                    alert = null,
                ),
            )
        } catch (error: AuthenticationException) {
            WeatherRemoteResult.AuthRequired(AUTH_REQUIRED)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            WeatherRemoteResult.Offline(OFFLINE)
        }
    }

    private fun fetchForecast(
        entityId: String,
        type: WeatherForecastType,
        headers: Map<String, String>,
    ): List<WeatherForecast> {
        val response = execute(
            HomeAssistantHttpRequest(
                method = "POST",
                url = "${endpoint.forecastUrl(entityId)}?return_response",
                headers = headers + ("Content-Type" to "application/json"),
                body = endpoint.forecastRequestBody(entityId, type),
            ),
        )
        return parser.parseForecastResponse(entityId, type, response.body)
    }

    private fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
        val response = transport.execute(request)
        when (response.statusCode) {
            401, 403 -> throw AuthenticationException()
        }
        if (response.statusCode !in 200..299) throw WeatherPayloadException("Weather request failed")
        return response
    }

    private class AuthenticationException : Exception()

    private companion object {
        const val AUTH_REQUIRED = "weather_auth_required"
        const val OFFLINE = "weather_offline"
    }
}
