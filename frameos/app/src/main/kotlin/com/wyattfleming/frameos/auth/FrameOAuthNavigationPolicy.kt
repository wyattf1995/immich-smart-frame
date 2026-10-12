package com.wyattfleming.frameos.auth

import com.wyattfleming.frameos.security.FrameUrlPolicy
import java.net.URI

/** Limits an embedded authorization session to one configured HA HTTPS origin. */
class FrameOAuthNavigationPolicy(
    private val configuredHomeAssistantUrl: String,
    callbackPageUrl: String,
    private val urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
) {
    private val callbackTarget = OAuthCallbackTarget.fromCallbackPageUrl(callbackPageUrl)

    enum class Decision { ALLOW, CALLBACK, DENY }

    init {
        requireConfiguredHttpsOrigin(configuredHomeAssistantUrl)
        require(callbackTarget.isOnConfiguredOrigin(configuredHomeAssistantUrl)) {
            "OAuth callback must stay on the configured origin"
        }
    }

    fun decide(requestUrl: String): Decision {
        if (callbackTarget.isNativeCallback(requestUrl)) return Decision.CALLBACK
        if (callbackTarget.isExactHttpsCallback(requestUrl)) return Decision.CALLBACK
        if (callbackTarget.isHttpsCallbackCandidate(requestUrl)) return Decision.DENY
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

}
