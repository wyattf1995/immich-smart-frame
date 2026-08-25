package com.wyattfleming.frameos.weather

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

interface CancellableHomeAssistantHttpTransport : HomeAssistantHttpTransport {
    fun cancelInFlight()
}

class UrlConnectionHomeAssistantTransport : CancellableHomeAssistantHttpTransport {
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override fun execute(request: HomeAssistantHttpRequest): HomeAssistantHttpResponse {
        val uri = requireSafeHttpsUrl(request.url)
        require(request.method == "GET" || request.method == "POST") { "Unsupported HTTP method" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connections += connection
        return try {
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
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= MAX_REQUEST_BYTES) { "HTTP request body is too large" }
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            HomeAssistantHttpResponse(statusCode, stream?.use(::readBoundedUtf8).orEmpty())
        } finally {
            connections -= connection
            connection.disconnect()
        }
    }

    override fun cancelInFlight() = connections.forEach(HttpURLConnection::disconnect)

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

    private fun readBoundedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
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
