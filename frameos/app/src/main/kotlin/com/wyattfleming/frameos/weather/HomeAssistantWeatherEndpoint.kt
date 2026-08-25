package com.wyattfleming.frameos.weather

import java.net.URI

class HomeAssistantWeatherEndpoint private constructor(
    private val origin: URI,
) {
    fun stateUrl(entityId: String): String {
        requireWeatherEntity(entityId)
        return origin.resolve("/api/states/$entityId").toASCIIString()
    }

    fun forecastUrl(entityId: String): String {
        requireWeatherEntity(entityId)
        return origin.resolve("/api/services/weather/get_forecasts").toASCIIString()
    }

    fun forecastRequestBody(entityId: String, forecastType: WeatherForecastType): String {
        requireWeatherEntity(entityId)
        return "{\"entity_id\":\"$entityId\",\"type\":\"${forecastType.apiValue}\"}"
    }

    fun requireSameOrigin(url: String): String {
        val requested = parseAbsoluteUrl(url)
        require(effectiveOrigin(requested) == effectiveOrigin(origin)) { "Weather API redirects must remain on the configured origin" }
        return url
    }

    companion object {
        private val weatherEntityPattern = Regex("weather\\.[a-z0-9_]+")

        fun fromDisplayUrl(displayUrl: String): HomeAssistantWeatherEndpoint {
            val configured = parseAbsoluteUrl(displayUrl)
            require(configured.scheme.equals("https", ignoreCase = true)) { "Native weather requires HTTPS" }
            require(configured.userInfo == null) { "Configured URL must not contain credentials" }
            val origin = URI(configured.scheme.lowercase(), null, configured.host, configured.port, "/", null, null)
            return HomeAssistantWeatherEndpoint(origin)
        }

        fun requireWeatherEntity(entityId: String) {
            require(weatherEntityPattern.matches(entityId)) { "A weather entity ID is required" }
        }

        private fun parseAbsoluteUrl(value: String): URI {
            val uri = try {
                URI(value)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid Home Assistant URL", error)
            }
            require(uri.isAbsolute && uri.host != null) { "An absolute Home Assistant URL is required" }
            require(uri.scheme.equals("https", ignoreCase = true)) { "Native weather requires HTTPS" }
            return uri
        }

        private fun effectiveOrigin(uri: URI): Triple<String, String, Int> {
            val scheme = uri.scheme.lowercase()
            val port = if (uri.port == -1) 443 else uri.port
            return Triple(scheme, uri.host.lowercase(), port)
        }
    }
}
