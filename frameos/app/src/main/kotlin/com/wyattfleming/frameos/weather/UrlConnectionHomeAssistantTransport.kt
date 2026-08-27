package com.wyattfleming.frameos.weather

import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

interface CancellableHomeAssistantHttpTransport : HomeAssistantHttpTransport {
    fun cancelInFlight()
}

class UrlConnectionHomeAssistantTransport(
    private val connectionFactory: (URI) -> HttpURLConnection = { uri -> uri.toURL().openConnection() as HttpURLConnection },
    private val cancellationRegistry: HomeAssistantHttpCancellationRegistry = HomeAssistantHttpCancellationRegistry(),
) : CancellableHomeAssistantHttpTransport {

    override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
        val uri = requireSafeHttpsUrl(request.url)
        require(request.method == "GET" || request.method == "POST") { "Unsupported HTTP method" }
        val requestGeneration = cancellationRegistry.beginRequest()
        val requestDeadline = request.timeoutMillis?.let { timeoutMillis ->
            WeatherRequestDeadline(timeoutMillis = timeoutMillis.coerceAtLeast(1L))
        }
        val connection = connectionFactory(uri)
        val requestHandle = HomeAssistantHttpRequestHandle(connection)
        if (!cancellationRegistry.register(requestGeneration, requestHandle)) throw cancellationFailure()
        return try {
            requireCurrent(requestGeneration, requestHandle, requestDeadline)
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = boundedTimeoutMillis(CONNECT_TIMEOUT_MILLIS, requestDeadline)
            connection.readTimeout = boundedTimeoutMillis(READ_TIMEOUT_MILLIS, requestDeadline)
            connection.useCaches = false
            request.headers.forEach { (name, value) ->
                require(!name.contains('\r') && !name.contains('\n'))
                require(!value.contains('\r') && !value.contains('\n'))
                connection.setRequestProperty(name, value)
            }
            val deadlineWatchdog = armDeadlineWatchdog(requestHandle, requestDeadline)
            try {
                request.body?.let { body ->
                    requireCurrent(requestGeneration, requestHandle, requestDeadline)
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    require(bytes.size <= MAX_REQUEST_BYTES) { "HTTP request body is too large" }
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                requireCurrent(requestGeneration, requestHandle, requestDeadline)
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { readBoundedUtf8(it, requestGeneration, requestHandle, requestDeadline) }.orEmpty()
                requireCurrent(requestGeneration, requestHandle, requestDeadline)
                HomeAssistantHttpResponse(statusCode, body)
            } catch (error: Exception) {
                throw resolveTransportFailure(error, requestHandle, requestDeadline)
            } finally {
                deadlineWatchdog.cancel(false)
            }
        } finally {
            cancellationRegistry.unregister(requestHandle)
            connection.disconnect()
        }
    }

    override fun cancelInFlight() = cancellationRegistry.cancelAll()

    override fun toString(): String = "UrlConnectionHomeAssistantTransport(redacted)"

    private fun requireSafeHttpsUrl(value: String): URI {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid Home Assistant request URL", error)
        }
        require(
            uri.isAbsolute &&
                uri.scheme.equals("https", ignoreCase = true) &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.fragment == null,
        ) { "Home Assistant requests require an absolute credential-free HTTPS URL" }
        return uri
    }

    private fun requireCurrent(requestGeneration: Long) {
        if (!cancellationRegistry.isCurrent(requestGeneration)) throw cancellationFailure()
    }

    private fun requireCurrent(
        requestGeneration: Long,
        requestHandle: HomeAssistantHttpRequestHandle,
        requestDeadline: WeatherRequestDeadline?,
    ) {
        if (requestDeadline?.isExpired() == true) {
            requestHandle.expireDeadline()
            throw deadlineFailure()
        }
        requireCurrent(requestGeneration)
        when {
            requestHandle.isDeadlineExpired() -> throw deadlineFailure()
            requestHandle.isCancelled() -> throw cancellationFailure()
        }
    }

    private fun readBoundedUtf8(
        input: java.io.InputStream,
        requestGeneration: Long,
        requestHandle: HomeAssistantHttpRequestHandle,
        requestDeadline: WeatherRequestDeadline?,
    ): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            requireCurrent(requestGeneration, requestHandle, requestDeadline)
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESPONSE_BYTES) { "Home Assistant response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun boundedTimeoutMillis(defaultTimeoutMillis: Int, requestDeadline: WeatherRequestDeadline?): Int {
        if (requestDeadline == null) return defaultTimeoutMillis
        val remainingMillis = requestDeadline.remainingMillis()
        if (remainingMillis == 0L) throw SocketTimeoutException("Home Assistant request deadline elapsed")
        return minOf(
            defaultTimeoutMillis,
            remainingMillis.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    private fun armDeadlineWatchdog(
        requestHandle: HomeAssistantHttpRequestHandle,
        requestDeadline: WeatherRequestDeadline?,
    ): ScheduledFuture<*> {
        if (requestDeadline == null) return NoOpScheduledFuture
        val remainingMillis = requestDeadline.remainingMillis()
        if (remainingMillis == 0L) {
            requestHandle.expireDeadline()
            throw deadlineFailure()
        }
        return deadlineWatchdogExecutor.schedule(
            { requestHandle.expireDeadline() },
            remainingMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun resolveTransportFailure(
        error: Exception,
        requestHandle: HomeAssistantHttpRequestHandle,
        requestDeadline: WeatherRequestDeadline?,
    ): Exception {
        return when {
            requestHandle.isCancelled() -> cancellationFailure(error)
            requestHandle.isDeadlineExpired() || requestDeadline?.isExpired() == true -> deadlineFailure(error)
            error is SocketTimeoutException -> deadlineFailure(error)
            else -> error
        }
    }

    private fun cancellationFailure(cause: Throwable? = null): InterruptedIOException =
        InterruptedIOException("Home Assistant request cancelled").also { failure ->
            cause?.let(failure::initCause)
        }

    private fun deadlineFailure(cause: Throwable? = null): SocketTimeoutException =
        SocketTimeoutException("Home Assistant request deadline elapsed").also { failure ->
            cause?.let(failure::initCause)
        }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val MAX_REQUEST_BYTES = 64 * 1_024
        const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
        val deadlineWatchdogExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "frameos-ha-http-deadline").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }
        val NoOpScheduledFuture = object : ScheduledFuture<Unit> {
            override fun getDelay(unit: TimeUnit): Long = 0L
            override fun compareTo(other: java.util.concurrent.Delayed): Int = 0
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override fun isCancelled(): Boolean = false
            override fun isDone(): Boolean = true
            override fun get(): Unit = Unit
            override fun get(timeout: Long, unit: TimeUnit): Unit = Unit
        }
    }
}
