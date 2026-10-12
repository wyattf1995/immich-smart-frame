package com.wyattfleming.frameos.auth

import com.wyattfleming.frameos.security.FrameUrlPolicy
import java.net.URI

/** Limits an embedded authorization session to one configured HA HTTPS origin. */
class FrameOAuthNavigationPolicy(
    private val configuredHomeAssistantUrl: String,
    private val urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
) {
    enum class Decision { ALLOW, CALLBACK, DENY }

    init {
        requireConfiguredHttpsOrigin(configuredHomeAssistantUrl)
    }

    fun decide(requestUrl: String): Decision {
        if (isCallbackCandidate(requestUrl)) return Decision.CALLBACK
        return if (urlPolicy.isAllowedTopLevelNavigation(configuredHomeAssistantUrl, requestUrl)) {
            Decision.ALLOW
        } else {
            Decision.DENY
        }
    }

    private fun requireConfiguredHttpsOrigin(configuredUrl: String) {
        val uri = runCatching { URI(configuredUrl) }
            .getOrElse { throw IllegalArgumentException("Home Assistant URL must be a valid HTTPS origin", it) }
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Home Assistant URL must use HTTPS"
        }
        require(uri.userInfo == null) { "Home Assistant URL must not include user info" }
        require(!uri.host.isNullOrBlank()) { "Home Assistant URL must include a host" }
    }

    private fun isCallbackCandidate(requestUrl: String): Boolean = runCatching {
        URI(requestUrl).let { uri ->
            uri.scheme == CALLBACK_SCHEME &&
                uri.host == CALLBACK_HOST &&
                uri.path == CALLBACK_PATH &&
                uri.userInfo == null &&
                uri.port == -1 &&
                uri.fragment == null
        }
    }.getOrDefault(false)

    private companion object {
        const val CALLBACK_SCHEME = "frameos"
        const val CALLBACK_HOST = "oauth"
        const val CALLBACK_PATH = "/callback"
    }
}
