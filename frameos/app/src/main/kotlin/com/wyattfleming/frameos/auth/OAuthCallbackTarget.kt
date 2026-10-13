package com.wyattfleming.frameos.auth

import java.net.URI

/** Immutable, exact HTTPS return target for an embedded Home Assistant authorization. */
class OAuthCallbackTarget private constructor(
    private val page: URI,
) {
    fun isNativeCallback(requestUrl: String): Boolean = parse(requestUrl)?.let(::isNativeCallback) == true

    fun isExactHttpsCallback(requestUrl: String): Boolean = parse(requestUrl)?.let(::isExactHttpsCallback) == true

    /**
     * Detects a malformed attempt to load the reserved return page so it cannot fall through to
     * normal same-origin browsing with authorization parameters in Gecko history or server logs.
     */
    fun isHttpsCallbackCandidate(requestUrl: String): Boolean = parse(requestUrl)?.let { uri ->
        uri.scheme.equals(page.scheme, ignoreCase = true) &&
            uri.host.equals(page.host, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(page) &&
            normalizedPathWithoutParameters(uri) == page.path
    } == true

    fun isOnConfiguredOrigin(configuredHomeAssistantUrl: String): Boolean = parse(configuredHomeAssistantUrl)?.let { configured ->
        configured.scheme.equals(page.scheme, ignoreCase = true) &&
            configured.host.equals(page.host, ignoreCase = true) &&
            effectivePort(configured) == effectivePort(page) &&
            configured.userInfo == null
    } == true

    private fun isExactHttpsCallback(uri: URI): Boolean =
        isHttpsCallbackCandidate(uri) &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.rawQuery != null &&
            uri.rawPath == page.rawPath

    private fun isHttpsCallbackCandidate(uri: URI): Boolean =
        uri.scheme.equals(page.scheme, ignoreCase = true) &&
            uri.host.equals(page.host, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(page) &&
            normalizedPathWithoutParameters(uri) == page.path

    private fun isNativeCallback(uri: URI): Boolean =
        uri.scheme == NATIVE_SCHEME &&
            uri.host == NATIVE_HOST &&
            uri.path == NATIVE_PATH &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.fragment == null &&
            uri.rawQuery != null

    private fun parse(value: String): URI? = runCatching { URI(value) }.getOrNull()

    companion object {
        private const val NATIVE_SCHEME = "frameos"
        private const val NATIVE_HOST = "oauth"
        private const val NATIVE_PATH = "/callback"

        private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port

        private fun normalizedPathWithoutParameters(uri: URI): String =
            URI(null, null, uri.path, null).normalize().path.substringBefore(';')

        fun fromCallbackPageUrl(callbackPageUrl: String): OAuthCallbackTarget {
            val uri = runCatching { URI(callbackPageUrl) }
                .getOrElse { throw IllegalArgumentException("OAuth callback page must be a valid HTTPS URL", it) }
            require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) && uri.host != null) {
                "OAuth callback page must be an absolute HTTPS URL"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "OAuth callback page must not contain credentials, a query, or a fragment"
            }
            require(uri.path == HomeAssistantOAuthEndpoint.CALLBACK_PATH) {
                "Unexpected OAuth callback path"
            }
            return OAuthCallbackTarget(uri)
        }
    }
}
