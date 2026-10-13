package com.wyattfleming.touchframe.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.net.URI

/**
 * One retained Gecko content session for Photos and Art. Home is a separate
 * opaque native panel, so hiding it never detaches or destroys this view.
 */
@SuppressLint("ViewConstructor")
class TouchMediaView(
    context: Context,
    private val runtime: GeckoRuntime,
    private val urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
) : FrameLayout(context) {
    var onStatus: ((String?) -> Unit)? = null

    private val requiresFullAccessibilityTree = context.getSystemService(AccessibilityManager::class.java)
        ?.isTouchExplorationEnabled == true
    private val geckoView = GeckoView(context).apply {
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val retryButton = TextView(context).apply {
        text = "Retry"
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(56)
        minimumWidth = context.dp(96)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.rgb(31, 112, 116))
            cornerRadius = context.dp(14).toFloat()
        }
        visibility = View.GONE
        setOnClickListener { retry() }
    }

    private var session: GeckoSession? = null
    private var configuredUrl: String? = null
    private var label: String = "Media"
    private var active = false
    private var closed = false
    private var contentProcessClosed = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(geckoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(retryButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(56), Gravity.CENTER))
        openSession()
    }

    /**
     * Loads a configured Photos/Art URL once. Returning to the same URL only
     * restores visibility; a changed, validated URL is the sole reload path.
     */
    fun showUrl(url: String, label: String) {
        check(!closed) { "TouchMediaView is closed" }
        require(urlPolicy.isSafeConfigurationUrl(url)) { "Media URL must be a safe configured origin" }
        this.label = label
        if (configuredUrl == url) return
        configuredUrl = url
        if (contentProcessClosed) {
            reportContentProcessClosed()
            return
        }
        retryButton.visibility = View.GONE
        onStatus?.invoke(null)
        session?.loadUri(url)
    }

    /** Keeps the attached view while stopping foreground Gecko work when hidden. */
    fun setVisibleActive(active: Boolean) {
        if (closed || this.active == active) return
        this.active = active
        geckoView.importantForAccessibility = if (active) {
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        session?.let { current ->
            current.setFocused(active)
            current.setActive(active)
        }
    }

    /** Idempotent lifecycle teardown; it cancels the session's pending network work. */
    fun close() {
        if (closed) return
        closed = true
        retryButton.visibility = View.GONE
        onStatus?.invoke(null)
        session?.let { current ->
            runCatching { current.setFocused(false) }
            runCatching { current.setActive(false) }
            runCatching { current.stop() }
            runCatching { geckoView.releaseSession() }
            runCatching { current.close() }
        }
        session = null
    }

    private fun retry() {
        val url = configuredUrl ?: return
        if (!urlPolicy.isAllowedTopLevelNavigation(url, url)) return
        retryButton.visibility = View.GONE
        onStatus?.invoke(null)
        if (contentProcessClosed) {
            session?.let { current ->
                runCatching { geckoView.releaseSession() }
                runCatching { current.close() }
            }
            session = null
            contentProcessClosed = false
            openSession()
        }
        session?.loadUri(url)
    }

    private fun openSession() {
        if (closed || session != null) return
        val next = GeckoSession(
            GeckoSessionSettings().apply {
                setAllowJavascript(true)
                setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
                setFullAccessibilityTree(requiresFullAccessibilityTree)
                setSuspendMediaWhenInactive(true)
            },
        )
        next.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                val allowed = configuredUrl?.let { configured ->
                    urlPolicy.isAllowedTopLevelNavigation(configured, request.uri)
                } == true
                if (!allowed) reportFailure("This media link is unavailable")
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            /** Null declines popup/new-window creation. */
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                reportFailure("Unable to load $label")
                return null
            }
        }
        next.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) reportReady()
                else reportFailure("Unable to load $label")
            }
        }
        next.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(session: GeckoSession) = reportReady()

            // Do not hand downloads or unknown content to an external activity.
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                reportFailure("Downloads are unavailable")
            }

            override fun onCrash(session: GeckoSession) = reportContentProcessClosed()

            override fun onKill(session: GeckoSession) = reportContentProcessClosed()
        }
        next.open(runtime)
        next.setActive(active)
        next.setFocused(active)
        geckoView.setSession(next)
        session = next
    }

    private fun reportReady() {
        if (closed) return
        retryButton.visibility = View.GONE
        onStatus?.invoke(null)
    }

    private fun reportFailure(message: String) {
        if (closed) return
        retryButton.visibility = View.VISIBLE
        onStatus?.invoke(message)
    }

    private fun reportContentProcessClosed() {
        if (closed) return
        contentProcessClosed = true
        retryButton.visibility = View.VISIBLE
        onStatus?.invoke("Unable to load $label")
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Restricts all in-session navigation to the parent-configured HTTP(S) origin. */
internal class FrameUrlPolicy {
    fun isSafeConfigurationUrl(value: String): Boolean {
        val uri = parse(value) ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }

    fun isAllowedTopLevelNavigation(configuredUrl: String, requestedUrl: String): Boolean {
        val configured = parse(configuredUrl) ?: return false
        val requested = parse(requestedUrl) ?: return false
        if (!isSafeConfigurationUrl(requestedUrl)) return false
        return configured.scheme.equals(requested.scheme, ignoreCase = true) &&
            configured.host.equals(requested.host, ignoreCase = true) &&
            port(configured) == port(requested)
    }

    private fun parse(value: String): URI? = runCatching { URI(value) }.getOrNull()

    private fun port(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }
}
