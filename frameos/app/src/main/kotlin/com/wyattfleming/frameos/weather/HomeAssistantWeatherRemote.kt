package com.wyattfleming.frameos.weather

import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException

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
    private val requestExecutor: ExecutorService = sharedRequestExecutor,
    private val requestTimeoutMillis: Long = TOTAL_REQUEST_TIMEOUT_MILLIS,
) : WeatherRemote {
    private val activeFutures = ConcurrentHashMap.newKeySet<Future<*>>()
    private val activeRequestLock = Any()
    private var cancellationRequested = false

    override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult {
        if (bearerToken.isBlank()) return WeatherRemoteResult.AuthRequired(AUTH_REQUIRED)
        synchronized(activeRequestLock) { cancellationRequested = false }
        var completed = false
        return try {
            val headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $bearerToken",
            )
            val deadline = WeatherRequestDeadline(timeoutMillis = requestTimeoutMillis)
            val currentFuture = submitTracked<WeatherCurrent> {
                parser.parseCurrentState(entityId, execute(HomeAssistantHttpRequest("GET", endpoint.stateUrl(entityId), headers)).body)
            }
            val dailyFuture = submitTracked<List<WeatherForecast>> {
                fetchForecast(entityId, WeatherForecastType.DAILY, headers)
            }
            val hourlyFuture = submitTracked<List<WeatherForecast>> {
                fetchForecast(entityId, WeatherForecastType.HOURLY, headers)
            }
            val current = await(currentFuture, deadline)
            val daily = await(dailyFuture, deadline)
            val hourly = await(hourlyFuture, deadline)
            WeatherRemoteResult.Success(
                WeatherSnapshot(
                    current = current,
                    daily = daily,
                    hourly = hourly,
                    alert = null,
                ),
            ).also { completed = true }
        } catch (error: AuthenticationException) {
            WeatherRemoteResult.AuthRequired(AUTH_REQUIRED)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            WeatherRemoteResult.Offline(OFFLINE)
        } finally {
            cancelOutstandingRequests(disconnect = !completed)
        }
    }

    override fun cancel() {
        cancelOutstandingRequests(disconnect = true)
    }

    private fun <T> await(future: Future<T>, deadline: WeatherRequestDeadline): T = try {
        val remainingMillis = deadline.remainingMillis()
        if (remainingMillis == 0L) throw TimeoutException("Weather request deadline elapsed")
        future.get(remainingMillis, TimeUnit.MILLISECONDS)
    } catch (error: ExecutionException) {
        throw (error.cause ?: error)
    }

    private fun cancelOutstandingRequests(disconnect: Boolean) {
        val futures = synchronized(activeRequestLock) {
            if (disconnect) cancellationRequested = true
            activeFutures.toList().also { activeFutures.clear() }
        }
        futures.forEach { future -> future.cancel(true) }
        if (disconnect) (transport as? CancellableHomeAssistantHttpTransport)?.cancelInFlight()
    }

    private fun <T> submitTracked(task: () -> T): Future<T> = synchronized(activeRequestLock) {
        if (cancellationRequested) throw CancellationException("Weather request cancelled")
        requestExecutor.submit(task).also(activeFutures::add)
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
        const val MAX_PARALLEL_REQUESTS = 3
        const val TOTAL_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val AUTH_REQUIRED = "weather_auth_required"
        const val OFFLINE = "weather_offline"
        val sharedRequestExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)
    }
}
