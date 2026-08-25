package com.wyattfleming.frameos.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

interface PendingOAuthStateStore {
    fun write(state: OAuthState)
    fun consume(): String?
}

class VerifiedAuthorizationCode(
    val code: String,
) {
    override fun toString(): String = "VerifiedAuthorizationCode(redacted)"
}

class OAuthCallbackVerifier(
    private val pendingStateStore: PendingOAuthStateStore,
) {
    fun verify(callbackUrl: String): VerifiedAuthorizationCode? {
        val uri = parseCallback(callbackUrl) ?: return null
        val parameters = parseQuery(uri.rawQuery) ?: return null
        val code = parameters.singleValue("code")?.takeIf(String::isNotBlank) ?: return consumeAndReject()
        val callbackState = parameters.singleValue("state")?.takeIf(String::isNotBlank) ?: return consumeAndReject()
        val expectedState = pendingStateStore.consume() ?: return null
        if (!constantTimeEquals(expectedState, callbackState)) return null
        return VerifiedAuthorizationCode(code)
    }

    override fun toString(): String = "OAuthCallbackVerifier(redacted)"

    private fun parseCallback(value: String): URI? = try {
        URI(value).takeIf { uri ->
            uri.scheme == CALLBACK_SCHEME &&
                uri.host == CALLBACK_HOST &&
                uri.path == CALLBACK_PATH &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.fragment == null &&
                uri.rawQuery != null
        }
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        null
    }

    private fun parseQuery(rawQuery: String): Map<String, List<String>>? = try {
        rawQuery.split('&')
            .filter(String::isNotBlank)
            .map { pair ->
                val separator = pair.indexOf('=')
                val rawKey = if (separator < 0) pair else pair.substring(0, separator)
                val rawValue = if (separator < 0) "" else pair.substring(separator + 1)
                decode(rawKey) to decode(rawValue)
            }
            .groupBy({ it.first }, { it.second })
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        null
    }

    private fun Map<String, List<String>>.singleValue(key: String): String? =
        get(key)?.takeIf { it.size == 1 }?.single()

    private fun consumeAndReject(): VerifiedAuthorizationCode? {
        pendingStateStore.consume()
        return null
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        actual.toByteArray(StandardCharsets.UTF_8),
    )

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val CALLBACK_SCHEME = "frameos"
        const val CALLBACK_HOST = "oauth"
        const val CALLBACK_PATH = "/callback"
    }
}
