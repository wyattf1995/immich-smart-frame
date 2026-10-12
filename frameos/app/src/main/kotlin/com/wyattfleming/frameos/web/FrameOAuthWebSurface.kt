package com.wyattfleming.frameos.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import com.wyattfleming.frameos.auth.FrameOAuthNavigationPolicy
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * A short-lived same-runtime Gecko session for Home Assistant OAuth.
 *
 * It deliberately has no external-activity handoff, navigation expansion,
 * download handler, or token/state storage. The caller owns state verification
 * and is responsible for calling [close] from lifecycle teardown.
 */
@SuppressLint("ViewConstructor")
class FrameOAuthWebSurface(
    context: Context,
    runtime: GeckoRuntime,
    authorizationUrl: String,
    configuredHomeAssistantUrl: String,
    private val onCallback: (String) -> Unit,
    private val onFailure: () -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val navigationPolicy: FrameOAuthNavigationPolicy =
        FrameOAuthNavigationPolicy(configuredHomeAssistantUrl),
) : GeckoView(context) {
    private val oauthSession = GeckoSession(
        GeckoSessionSettings().apply {
            setAllowJavascript(true)
            setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
            setSuspendMediaWhenInactive(true)
        },
    )
    private var closed = false
    private var callbackDelivered = false
    private var failureDelivered = false
    private val timeout = Runnable { failAndClose() }

    init {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "OAuth timeout must be bounded" }
        require(navigationPolicy.decide(authorizationUrl) == FrameOAuthNavigationPolicy.Decision.ALLOW) {
            "OAuth authorization URL must stay on the configured HTTPS origin"
        }
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        setBackgroundColor(Color.BLACK)
        coverUntilFirstPaint(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        oauthSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> = when (navigationPolicy.decide(request.uri)) {
                FrameOAuthNavigationPolicy.Decision.ALLOW -> GeckoResult.fromValue(AllowOrDeny.ALLOW)
                FrameOAuthNavigationPolicy.Decision.CALLBACK -> {
                    deliverCallback(request.uri)
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                FrameOAuthNavigationPolicy.Decision.DENY -> {
                    failAndClose()
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
        }
        oauthSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) failAndClose()
            }
        }
        oauthSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) = failAndClose()
            override fun onKill(session: GeckoSession) = failAndClose()
        }
        oauthSession.open(runtime)
        oauthSession.setActive(true)
        oauthSession.setFocused(true)
        setSession(oauthSession)
        postDelayed(timeout, timeoutMillis)
        oauthSession.loadUri(authorizationUrl)
    }

    /** Idempotent lifecycle close; it never reports a failure by itself. */
    fun close() {
        if (closed) return
        closed = true
        removeCallbacks(timeout)
        if (session != null) runCatching(::releaseSession)
        runCatching {
            oauthSession.setFocused(false)
            oauthSession.setActive(false)
            oauthSession.stop()
            oauthSession.close()
        }
    }

    private fun deliverCallback(callbackUrl: String) {
        if (closed || callbackDelivered) return
        callbackDelivered = true
        close()
        onCallback(callbackUrl)
    }

    private fun failAndClose() {
        if (closed) return
        close()
        if (failureDelivered) return
        failureDelivered = true
        onFailure()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 120_000L
        const val MAX_TIMEOUT_MILLIS = 120_000L
    }
}
