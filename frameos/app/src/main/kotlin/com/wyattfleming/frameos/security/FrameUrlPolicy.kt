package com.wyattfleming.frameos.security

import java.net.URI

class FrameUrlPolicy {
    fun isSafeConfigurationUrl(value: String): Boolean {
        val uri = value.parseUri() ?: return false
        if (uri.scheme.lowercase() !in ALLOWED_SCHEMES) return false
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return false

        return uri.rawQuery
            ?.split('&')
            ?.map { parameter -> parameter.substringBefore('=').lowercase() }
            ?.none { key -> SECRET_QUERY_KEYS.any(key::contains) }
            ?: true
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

    private fun URI.effectivePort(): Int = when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https")
        val SECRET_QUERY_KEYS = setOf("access_token", "api_key", "password", "secret")
    }
}
