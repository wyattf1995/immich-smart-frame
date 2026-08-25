package com.wyattfleming.frameos.weather

import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch

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
    private val fallbackEndpoint: HomeAssistantWeatherEndpoint? = null,
    private val requestExecutor: ExecutorService = sharedRequestExecutor,
    private val requestTimeoutMillis: Long = TOTAL_REQUEST_TIMEOUT_MILLIS,
) : WeatherRemote {
    private val batchLock = Any()
    private var activeBatch: RequestBatch? = null

    override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult {
        if (bearerToken.isBlank()) return WeatherRemoteResult.AuthRequired(AUTH_REQUIRED)
        val primaryResult = fetchFrom(endpoint, entityId, bearerToken)
        return if (primaryResult is WeatherRemoteResult.Offline && fallbackEndpoint != null) {
            fetchFrom(fallbackEndpoint, entityId, bearerToken = null)
        } else {
            primaryResult
        }
    }

    private fun fetchFrom(
        requestEndpoint: HomeAssistantWeatherEndpoint,
        entityId: String,
        bearerToken: String?,
    ): WeatherRemoteResult {
        val batch = synchronized(batchLock) {
            if (activeBatch != null) return WeatherRemoteResult.Offline(REQUEST_IN_PROGRESS)
            RequestBatch().also { activeBatch = it }
        }
        var completed = false
        return try {
            val headers = buildMap {
                put("Accept", "application/json")
                bearerToken?.let { put("Authorization", "Bearer $it") }
            }
            val deadline = WeatherRequestDeadline(timeoutMillis = requestTimeoutMillis)
            val currentFuture = submitTracked(batch) {
                parser.parseCurrentState(entityId, execute(HomeAssistantHttpRequest("GET", requestEndpoint.stateUrl(entityId), headers)).body)
            }
            val dailyFuture = submitTracked(batch) {
                fetchForecast(requestEndpoint, entityId, WeatherForecastType.DAILY, headers)
            }
            val hourlyFuture = submitTracked(batch) {
                fetchForecast(requestEndpoint, entityId, WeatherForecastType.HOURLY, headers)
            }
            awaitBatchStart(batch, deadline)
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
            cancelBatch(batch, disconnect = !completed)
            synchronized(batchLock) {
                if (activeBatch === batch) activeBatch = null
            }
        }
    }

    override fun cancel() {
        synchronized(batchLock) { activeBatch }?.let { batch -> cancelBatch(batch, disconnect = true) }
    }

    private fun <T> await(future: Future<T>, deadline: WeatherRequestDeadline): T = try {
        val remainingMillis = deadline.remainingMillis()
        if (remainingMillis == 0L) throw TimeoutException("Weather request deadline elapsed")
        future.get(remainingMillis, TimeUnit.MILLISECONDS)
    } catch (error: ExecutionException) {
        throw (error.cause ?: error)
    }

    private fun awaitBatchStart(batch: RequestBatch, deadline: WeatherRequestDeadline) {
        val remainingMillis = deadline.remainingMillis()
        if (remainingMillis == 0L || !batch.started.await(remainingMillis, TimeUnit.MILLISECONDS)) {
            throw TimeoutException("Weather request deadline elapsed")
        }
    }

    private fun cancelBatch(batch: RequestBatch, disconnect: Boolean) {
        val futures = synchronized(batchLock) {
            if (disconnect) batch.cancelled = true
            batch.futures.toList().also { batch.futures.clear() }
        }
        futures.forEach { future -> future.cancel(true) }
        if (disconnect) (transport as? CancellableHomeAssistantHttpTransport)?.cancelInFlight()
    }

    private fun <T> submitTracked(batch: RequestBatch, task: () -> T): Future<T> = synchronized(batchLock) {
        if (activeBatch !== batch || batch.cancelled) throw CancellationException("Weather request cancelled")
        requestExecutor.submit<T> {
            batch.started.countDown()
            task()
        }.also(batch.futures::add)
    }

    private fun fetchForecast(
        endpoint: HomeAssistantWeatherEndpoint,
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

    private class RequestBatch {
        val futures = mutableSetOf<Future<*>>()
        val started = CountDownLatch(MAX_PARALLEL_REQUESTS)
        var cancelled = false
    }

    private companion object {
        const val MAX_PARALLEL_REQUESTS = 3
        const val TOTAL_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val AUTH_REQUIRED = "weather_auth_required"
        const val OFFLINE = "weather_offline"
        const val REQUEST_IN_PROGRESS = "weather_request_in_progress"
        val sharedRequestExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)
    }
}
