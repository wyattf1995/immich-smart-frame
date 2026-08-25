package com.wyattfleming.frameos.weather

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.io.InterruptedIOException

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
        requireCurrent(requestGeneration)
        val connection = connectionFactory(uri)
        if (!cancellationRegistry.register(requestGeneration, connection)) throw InterruptedIOException("Home Assistant request cancelled")
        return try {
            requireCurrent(requestGeneration)
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            request.headers.forEach { (name, value) ->
                require(!name.contains('\r') && !name.contains('\n'))
                require(!value.contains('\r') && !value.contains('\n'))
                connection.setRequestProperty(name, value)
            }
            request.body?.let { body ->
                requireCurrent(requestGeneration)
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_REQUEST_BYTES) { "HTTP request body is too large" }
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            requireCurrent(requestGeneration)
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            HomeAssistantHttpResponse(statusCode, stream?.use { readBoundedUtf8(it, requestGeneration) }.orEmpty())
        } finally {
            cancellationRegistry.unregister(connection)
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
        if (!cancellationRegistry.isCurrent(requestGeneration)) throw InterruptedIOException("Home Assistant request cancelled")
    }

    private fun readBoundedUtf8(input: java.io.InputStream, requestGeneration: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            requireCurrent(requestGeneration)
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESPONSE_BYTES) { "Home Assistant response is too large" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 12_000
        const val MAX_REQUEST_BYTES = 64 * 1_024
        const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
    }
}
