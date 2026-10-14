package com.wyattfleming.touchframe.ocean

import com.wyattfleming.frameos.weather.LegacyIsrgRootTls
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal enum class TouchOceanConnection { LIVE, OFFLINE }

internal data class TouchOceanLoad(val conditions: TouchOceanConditions?, val connection: TouchOceanConnection)

internal data class TouchOceanState(
    val conditions: TouchOceanConditions? = null,
    val connection: TouchOceanConnection = TouchOceanConnection.OFFLINE,
    val loading: Boolean = false,
    val fetchedAtMillis: Long? = null,
)

/**
 * Public beach conditions from Ebbward (NOAA blend), no account or token. The last good body is kept so a
 * Wi-Fi drop shows saved conditions with their time instead of an empty screen. Blocking: call off the UI thread.
 */
internal class TouchOceanRepository(
    private val cacheFile: File,
    private val parser: TouchOceanParser = TouchOceanParser(),
    private val transport: (URI, Int) -> String = { uri, timeout -> httpGet(uri, timeout) },
) {
    fun load(spot: String, timeoutMillis: Int): TouchOceanLoad {
        require(SPOT.matches(spot)) { "Unknown beach id" }
        val live = runCatching { transport(URI("$ENDPOINT?spot=$spot"), timeoutMillis) }.getOrNull()
            ?.let { body -> parser.parse(body)?.takeIf { it.spotId == spot }?.also { writeCache(body) } }
        if (live != null) return TouchOceanLoad(live, TouchOceanConnection.LIVE)
        val cached = readCache()?.let(parser::parse)?.takeIf { it.spotId == spot }
        return TouchOceanLoad(cached, TouchOceanConnection.OFFLINE)
    }

    private fun readCache(): String? = runCatching {
        if (cacheFile.isFile && cacheFile.length() <= TouchOceanParser.MAX_BODY_CHARS) cacheFile.readText(Charsets.UTF_8) else null
    }.getOrNull()

    private fun writeCache(body: String) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val temporary = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            temporary.writeText(body, Charsets.UTF_8)
            if (!temporary.renameTo(cacheFile)) temporary.delete()
        }
    }

    companion object {
        /** Main Beach, Laguna Beach, CA: the default until frames carry their own beach setting. */
        const val DEFAULT_SPOT = "main-beach"
        const val ENDPOINT = "https://ebbward.com/api/v1/ocean"
        private val SPOT = Regex("[a-z0-9][a-z0-9-]{0,63}")

        fun httpGet(uri: URI, timeoutMillis: Int): String {
            val connection: HttpURLConnection = LegacyIsrgRootTls.ConnectionFactory().open(uri)
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) throw IOException("Ebbward answered ${connection.responseCode}")
                return connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > TouchOceanParser.MAX_BODY_CHARS) throw IOException("Ocean response exceeds bound")
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
