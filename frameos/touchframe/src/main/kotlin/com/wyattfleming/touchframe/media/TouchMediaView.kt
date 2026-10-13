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
import com.wyattfleming.frameos.security.FrameUrlPolicy
import com.wyattfleming.touchframe.R
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.Future

/**
 * One retained Gecko content session for Photos and Art. Home is a separate
 * opaque native panel, so hiding it never detaches or destroys this view.
 */
@SuppressLint("ViewConstructor")
class TouchMediaView(
    context: Context,
    private val runtime: GeckoRuntime,
    private val urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
    photoStore: TouchOfflinePhotoStore? = null,
) : FrameLayout(context) {
    var onStatus: ((String?) -> Unit)? = null
    var onPhotoObserved: ((com.wyattfleming.frameos.photos.FramePhotoBridge.PhotoObserved) -> Unit)? = null
    private val ownsPhotoStore = photoStore == null
    private val offlineStore = photoStore ?: TouchOfflinePhotoStore(context)

    private val requiresFullAccessibilityTree = context.getSystemService(AccessibilityManager::class.java)
        ?.isTouchExplorationEnabled == true
    private val geckoView = GeckoView(context).apply {
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val retryButton = TextView(context).apply {
        text = context.getString(R.string.touch_media_retry)
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
    private val offlinePhotos = TouchOfflinePhotos(context, offlineStore) { photo -> onPhotoObserved?.invoke(photo) }
    private var photosProfile: String? = null
    private val loadState = TouchMediaLoadState()
    private val configuredUrl: String? get() = loadState.configuredUrl
    private var label: String = "Media"
    private var active = false
    private var closed = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(geckoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(offlinePhotos.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(retryButton, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(56), Gravity.CENTER))
        openSession()
    }

    /**
     * Loads a configured Photos/Art URL once. Returning to the same URL only
     * restores visibility; a changed, validated URL is the sole reload path.
     */
    fun showPhotos(url: String, profile: String) {
        photosProfile = profile
        showUrl(url, "Photos")
        session?.let { offlinePhotos.attach(runtime, it, url, profile, active) }
    }

    fun showArt(url: String) {
        photosProfile = null
        session?.let(offlinePhotos::detach)
        showUrl(url, "Art")
    }

    fun setPhotosPaused(paused: Boolean): Boolean = photosProfile != null && offlinePhotos.setPaused(paused)

    fun movePhoto(forward: Boolean): Boolean = photosProfile != null && offlinePhotos.move(forward)

    fun applyHiddenPhotos(assetIds: Set<String>, canCommit: () -> Boolean = { true }): Future<Boolean> =
        offlineStore.applyHiddenAssets(assetIds, canCommit)

    fun activateOfflinePhotosScope(validatedPhotosUrl: String, profile: String): Future<Boolean> =
        offlineStore.activateScope(validatedPhotosUrl, profile)

    fun hideOfflinePhotoIfHidden(assetIds: Set<String>): Boolean = offlinePhotos.hideFallbackIfHidden(assetIds)

    val offlineVisible: Boolean get() = offlinePhotos.isOfflineVisible()

    fun offlineStatus(): com.wyattfleming.frameos.photos.FrameOfflineReserve.Status = offlineStore.publishedStatus()

    fun showUrl(url: String, label: String) {
        if (label != "Photos") {
            photosProfile = null
            session?.let(offlinePhotos::detach)
        }
        check(!closed) { "TouchMediaView is closed" }
        require(urlPolicy.isSafeConfigurationUrl(url)) { "Media URL must be a safe configured origin" }
        this.label = label
        when (loadState.showUrl(url)) {
            TouchMediaLoadState.Decision.ALREADY_LOADED -> return
            TouchMediaLoadState.Decision.RETRY_REQUIRED -> {
                reportContentProcessClosed()
                return
            }
            TouchMediaLoadState.Decision.RECOVER_RETAINED_SESSION -> {
                reopenAndLoad(url)
            }
            TouchMediaLoadState.Decision.LOAD -> {
                loadConfiguredUrl(url)
            }
        }
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
        offlinePhotos.setActive(active && photosProfile != null)
    }

    /** Idempotent lifecycle teardown; it cancels the session's pending network work. */
    fun close() {
        if (closed) return
        closed = true
        retryButton.visibility = View.GONE
        offlinePhotos.close()
        if (ownsPhotoStore) offlineStore.close()
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
        val request = loadState.retry() ?: return
        retryButton.visibility = View.GONE
        offlinePhotos.hideFallback()
        onStatus?.invoke(null)
        if (request.reopenSession) {
            reopenAndLoad(request.url)
        } else {
            loadConfiguredUrl(request.url)
        }
    }

    /**
     * A content process killed while this retained surface was hidden has no
     * usable session to restore. Recreate it once on the next user return.
     * Later active crashes remain visible and require the explicit Retry action.
     */
    private fun reopenAndLoad(url: String) {
        session?.let(offlinePhotos::detach)
        session?.let { current ->
            runCatching { geckoView.releaseSession() }
            runCatching { current.close() }
        }
        session = null
        openSession()
        loadConfiguredUrl(url)
    }

    private fun loadConfiguredUrl(url: String) {
        retryButton.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        onStatus?.invoke(null)
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
                if (!isCurrentSession(session)) return GeckoResult.fromValue(AllowOrDeny.DENY)
                val allowed = configuredUrl?.let { configured ->
                    urlPolicy.isAllowedTopLevelNavigation(configured, request.uri)
                } == true
                if (!allowed) reportFailure(session, context.getString(R.string.touch_media_link_unavailable))
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            /** Null declines popup/new-window creation. */
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                reportFailure(session, context.getString(R.string.touch_media_load_failed, label))
                return null
            }
        }
        next.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) reportReady(session)
                else reportFailure(session, context.getString(R.string.touch_media_load_failed, label))
            }
        }
        next.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(session: GeckoSession) = reportReady(session)

            // Do not hand downloads or unknown content to an external activity.
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                reportFailure(session, context.getString(R.string.touch_media_downloads_unavailable))
            }

            override fun onCrash(session: GeckoSession) = reportContentProcessClosed(session)

            override fun onKill(session: GeckoSession) = reportContentProcessClosed(session)
        }
        next.open(runtime)
        next.setActive(active)
        next.setFocused(active)
        session = next
        geckoView.setSession(next)
        photosProfile?.let { profile -> configuredUrl?.let { url -> offlinePhotos.attach(runtime, next, url, profile, active) } }
    }

    private fun reportReady(source: GeckoSession) {
        if (!isCurrentSession(source)) return
        geckoView.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        onStatus?.invoke(null)
    }

    private fun reportFailure(source: GeckoSession, message: String) {
        if (!isCurrentSession(source)) return
        // Gecko's failed document surface is white on this API level. Keep the
        // shell's intentional black media background visible behind Retry.
        geckoView.visibility = View.INVISIBLE
        retryButton.visibility = View.VISIBLE
        if (photosProfile != null) offlinePhotos.showFallback()
        onStatus?.invoke(message)
    }

    private fun reportContentProcessClosed(source: GeckoSession? = session) {
        if (source == null || !isCurrentSession(source)) return
        loadState.markContentProcessClosed(whileHidden = !active)
        geckoView.visibility = View.INVISIBLE
        retryButton.visibility = View.VISIBLE
        if (photosProfile != null) offlinePhotos.showFallback()
        onStatus?.invoke(context.getString(R.string.touch_media_load_failed, label))
    }

    private fun isCurrentSession(candidate: GeckoSession): Boolean = !closed && session === candidate

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Pure load/retry decisions, kept free of Gecko and Android for focused tests. */
internal class TouchMediaLoadState {
    enum class Decision { LOAD, ALREADY_LOADED, RECOVER_RETAINED_SESSION, RETRY_REQUIRED }

    data class RetryRequest(val url: String, val reopenSession: Boolean)

    var configuredUrl: String? = null
        private set
    private var contentProcessClosed = false
    private var hiddenReturnRecoveryAvailable = false

    fun showUrl(url: String): Decision {
        if (contentProcessClosed) {
            configuredUrl = url
            return if (hiddenReturnRecoveryAvailable) {
                contentProcessClosed = false
                hiddenReturnRecoveryAvailable = false
                Decision.RECOVER_RETAINED_SESSION
            } else {
                Decision.RETRY_REQUIRED
            }
        }
        if (configuredUrl == url) return Decision.ALREADY_LOADED
        configuredUrl = url
        return Decision.LOAD
    }

    fun markContentProcessClosed(whileHidden: Boolean = false) {
        contentProcessClosed = true
        hiddenReturnRecoveryAvailable = whileHidden
    }

    fun retry(): RetryRequest? {
        val url = configuredUrl ?: return null
        val reopen = contentProcessClosed
        contentProcessClosed = false
        hiddenReturnRecoveryAvailable = false
        return RetryRequest(url, reopen)
    }
}
