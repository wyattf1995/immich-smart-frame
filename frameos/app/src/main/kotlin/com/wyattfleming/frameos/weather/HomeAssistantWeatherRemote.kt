package com.wyattfleming.frameos.weather

import com.wyattfleming.frameos.FrameOperationLogEntry
import com.wyattfleming.frameos.FrameOperationLogger
import com.wyattfleming.frameos.NoOpFrameOperationLogger
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
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
    val timeoutMillis: Long? = null,
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
    private val operationLogger: FrameOperationLogger = NoOpFrameOperationLogger,
    private val clockNanos: () -> Long = System::nanoTime,
) : DeadlineAwareWeatherRemote {
    private val batchLock = Any()
    private var activeBatch: RequestBatch? = null
    private val cancellableTransport = transport as? CancellableHomeAssistantHttpTransport

    override fun fetch(entityId: String, bearerToken: String): WeatherRemoteResult {
        val deadline = WeatherRequestDeadline(timeoutMillis = requestTimeoutMillis)
        return fetch(entityId, bearerToken, deadline)
    }

    override fun fetch(
        entityId: String,
        bearerToken: String,
        deadline: WeatherRequestDeadline,
    ): WeatherRemoteResult {
        if (bearerToken.isBlank()) return WeatherRemoteResult.AuthRequired(AUTH_REQUIRED)
        val primaryResult = fetchFrom(
            requestEndpoint = endpoint,
            entityId = entityId,
            bearerToken = bearerToken,
            deadline = primaryDeadline(deadline),
            route = "weather_primary",
        )
        return if (
            primaryResult is WeatherRemoteResult.Offline &&
            fallbackEndpoint != null &&
            deadline.remainingMillis() > 0L
        ) {
            fetchFrom(
                requestEndpoint = fallbackEndpoint,
                entityId = entityId,
                bearerToken = null,
                deadline = WeatherRequestDeadline(timeoutMillis = deadline.remainingMillis()),
                route = "weather_fallback",
            )
        } else {
            primaryResult
        }
    }

    private fun fetchFrom(
        requestEndpoint: HomeAssistantWeatherEndpoint,
        entityId: String,
        bearerToken: String?,
        deadline: WeatherRequestDeadline,
        route: String,
    ): WeatherRemoteResult {
        val startedAtNanos = clockNanos()
        if (deadline.remainingMillis() == 0L) {
            logFailure(route, "refresh", TimeoutException("Weather request deadline elapsed"), startedAtNanos)
            return WeatherRemoteResult.Offline(OFFLINE)
        }
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
            val currentFuture = submitTracked(batch) {
                parser.parseCurrentState(
                    entityId,
                    execute(
                        HomeAssistantHttpRequest(
                            method = "GET",
                            url = requestEndpoint.stateUrl(entityId),
                            headers = headers,
                            timeoutMillis = deadline.remainingMillis(),
                        ),
                    ).body,
                )
            }
            val dailyFuture = submitTracked(batch) {
                fetchForecast(requestEndpoint, entityId, WeatherForecastType.DAILY, headers, deadline)
            }
            val hourlyFuture = submitTracked(batch) {
                fetchForecast(requestEndpoint, entityId, WeatherForecastType.HOURLY, headers, deadline)
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
            logFailure(route, "refresh", error, startedAtNanos)
            WeatherRemoteResult.Offline(OFFLINE)
        } finally {
            finishBatch(batch, completed)
        }
    }

    override fun cancel() {
        applyBatchCleanup(
            synchronized(batchLock) {
                val batch = activeBatch ?: return
                activeBatch = null
                batch.cancelled = true
                batch.releaseStartWaiters()
                BatchCleanup(
                    futures = batch.futures.toList().also { batch.futures.clear() },
                    cancelTransport = batch.issueTransportCancellation(),
                )
            },
        )
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

    private fun finishBatch(batch: RequestBatch, completed: Boolean) {
        applyBatchCleanup(
            synchronized(batchLock) {
                if (activeBatch === batch) activeBatch = null
                if (completed) {
                    batch.futures.clear()
                    BatchCleanup(emptyList(), cancelTransport = false)
                } else {
                    batch.cancelled = true
                    batch.releaseStartWaiters()
                    BatchCleanup(
                        futures = batch.futures.toList().also { batch.futures.clear() },
                        cancelTransport = batch.issueTransportCancellation(),
                    )
                }
            },
        )
    }

    private fun applyBatchCleanup(cleanup: BatchCleanup) {
        cleanup.futures.forEach { future -> future.cancel(true) }
        if (cleanup.cancelTransport) cancellableTransport?.cancelInFlight()
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
        deadline: WeatherRequestDeadline,
    ): List<WeatherForecast> {
        val response = execute(
            HomeAssistantHttpRequest(
                method = "POST",
                url = "${endpoint.forecastUrl(entityId)}?return_response",
                headers = headers + ("Content-Type" to "application/json"),
                body = endpoint.forecastRequestBody(entityId, type),
                timeoutMillis = deadline.remainingMillis(),
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
        private var transportCancellationIssued = false

        fun releaseStartWaiters() = repeat(MAX_PARALLEL_REQUESTS) { started.countDown() }

        fun issueTransportCancellation(): Boolean =
            if (transportCancellationIssued) {
                false
            } else {
                transportCancellationIssued = true
                true
            }
    }

    private data class BatchCleanup(
        val futures: List<Future<*>>,
        val cancelTransport: Boolean,
    )

    private companion object {
        const val MAX_PARALLEL_REQUESTS = 3
        const val TOTAL_REQUEST_TIMEOUT_MILLIS = 15_000L
        const val FALLBACK_RESERVE_MILLIS = 4_000L
        const val AUTH_REQUIRED = "weather_auth_required"
        const val OFFLINE = "weather_offline"
        const val REQUEST_IN_PROGRESS = "weather_request_in_progress"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        val sharedRequestExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL_REQUESTS)
    }

    private fun primaryDeadline(deadline: WeatherRequestDeadline): WeatherRequestDeadline {
        val remainingMillis = deadline.remainingMillis()
        if (remainingMillis == 0L) return WeatherRequestDeadline(timeoutMillis = 1L)
        if (fallbackEndpoint == null || remainingMillis <= FALLBACK_RESERVE_MILLIS + 1L) {
            return WeatherRequestDeadline(timeoutMillis = remainingMillis)
        }
        return WeatherRequestDeadline(
            timeoutMillis = (remainingMillis - FALLBACK_RESERVE_MILLIS).coerceAtLeast(1L),
        )
    }

    private fun logFailure(
        route: String,
        stage: String,
        error: Throwable,
        startedAtNanos: Long,
    ) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        operationLogger.log(
            FrameOperationLogEntry(
                route = route,
                stage = stage,
                outcome = when (error) {
                    is CancellationException, is InterruptedException -> "cancelled"
                    is InterruptedIOException ->
                        if (error.message?.contains("cancelled", ignoreCase = true) == true) "cancelled" else "timeout"
                    is SocketTimeoutException, is TimeoutException -> "timeout"
                    else -> "offline"
                },
                elapsedMillis = ((clockNanos() - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND,
            ),
        )
    }
}
