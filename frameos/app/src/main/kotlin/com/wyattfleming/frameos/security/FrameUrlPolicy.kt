package com.wyattfleming.frameos.security

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class FrameUrlPolicy {
    fun isSafeConfigurationUrl(value: String): Boolean {
        val uri = value.parseUri() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in ALLOWED_SCHEMES) return false
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return false

        return !containsSecretParameters(uri.rawQuery) &&
            !containsSecretParameters(uri.rawFragment)
    }

    fun isAllowedTopLevelNavigation(configuredUrl: String, requestedUrl: String): Boolean {
        val configured = configuredUrl.parseUri() ?: return false
        val requested = requestedUrl.parseUri() ?: return false
        if (!isSafeConfigurationUrl(requestedUrl)) return false

        return configured.scheme.equals(requested.scheme, ignoreCase = true) &&
            configured.host.equals(requested.host, ignoreCase = true) &&
            configured.effectivePort() == requested.effectivePort()
    }

    private fun String.parseUri(): URI? = runCatching { URI(this) }.getOrNull()

    private fun containsSecretParameters(rawParameters: String?): Boolean {
        if (rawParameters.isNullOrBlank()) return false

        return rawParameters
            .split('&', ';')
            .map { parameter -> parameter.substringBefore('=').decodeParameterName() }
            .any { key ->
                key in SECRET_QUERY_KEYS ||
                    key.endsWith("_token") ||
                    key.endsWith("_key") ||
                    SECRET_QUERY_KEYS.any(key::contains)
            }
    }

    private fun String.decodeParameterName(): String {
        var decoded = this
        repeat(MAX_DECODE_PASSES) {
            decoded = runCatching {
                URLDecoder.decode(decoded, StandardCharsets.UTF_8.name())
            }.getOrDefault(decoded)
        }
        return decoded.trim().lowercase()
    }

    private fun URI.effectivePort(): Int = when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https")
        val SECRET_QUERY_KEYS = setOf(
            "access_token",
            "api_key",
            "auth",
            "authorization",
            "credential",
            "password",
            "secret",
            "token",
        )
        const val MAX_DECODE_PASSES = 2
    }
}
