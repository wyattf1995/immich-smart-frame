package com.wyattfleming.frameos.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.wyattfleming.frameos.security.FrameUrlPolicy
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.net.URI

@SuppressLint("ViewConstructor")
class FrameWebSurface(
    context: Context,
    runtime: GeckoRuntime,
    photosUrl: String,
    homeAssistantUrl: String,
    homeAssistantFallbackUrl: String? = null,
    private val listener: Listener,
    private val urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
) : GeckoView(context) {
    interface Listener {
        fun onPageLoading(label: String, loading: Boolean)
        fun onPageUnavailable(label: String)
        fun onPageRecovery(label: String, attempt: Int, retryInMillis: Long)
        fun onPageRecovered(label: String)
    }

    private val frameRuntime = runtime
    private val retentionPolicy = FrameWebSessionRetentionPolicy()
    private val requiresFullAccessibilityTree = context.getSystemService(AccessibilityManager::class.java)
        ?.isTouchExplorationEnabled == true
    private val recoveryPolicy = FrameWebRecoveryPolicy()

    private inner class ManagedSession(
        val configuredUrls: List<String>,
        val label: String,
    ) {
        lateinit var session: GeckoSession
            private set
        val loadState = FrameWebSessionState()
        val crashRecovery = FrameWebCrashRecoveryState()
        var recoveryAttempts = 0
        var retryPending = false

        init {
            openSession()
        }

        private fun openSession() {
            session = GeckoSession(
            GeckoSessionSettings().apply {
                setAllowJavascript(true)
                setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
                setFullAccessibilityTree(requiresFullAccessibilityTree)
                setSuspendMediaWhenInactive(true)
            },
            )
            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny> {
                    val allowed = urlPolicy.isAllowedTopLevelNavigation(configuredUrls, request.uri)
                    if (!allowed) reportUnavailable(this@ManagedSession)
                    return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                }
            }
            session.permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    perm: GeckoSession.PermissionDelegate.ContentPermission,
                ): GeckoResult<Int>? = FrameContentPermissionPolicy.responseFor(perm.permission)
                    ?.let { response -> GeckoResult.fromValue(response) }
            }
            session.progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    listener.onPageLoading(label, true)
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    listener.onPageLoading(label, false)
                    if (!success) {
                        reportUnavailable(this@ManagedSession)
                    } else {
                        loadState.recordSuccess()
                        resetRecovery(this@ManagedSession)
                        listener.onPageRecovered(label)
                    }
                }
            }
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    crashRecovery.markClosedByContentProcess()
                    reportUnavailable(this@ManagedSession)
                }

                override fun onKill(session: GeckoSession) {
                    crashRecovery.markClosedByContentProcess()
                    reportUnavailable(this@ManagedSession)
                }
            }
            session.open(frameRuntime)
            session.setActive(false)
            session.setFocused(false)
        }

        fun request(url: String): Boolean {
            require(urlPolicy.isAllowedTopLevelNavigation(configuredUrls, url)) { "Unsafe $label navigation" }
            loadState.recordRequest(url)
            try {
                if (crashRecovery.reopenIfRequired { session.open(frameRuntime) }) {
                    // Recovery retries run only for an attached, visible session.
                    session.setActive(true)
                    session.setFocused(false)
                }
                session.loadUri(url)
                return true
            } catch (error: RuntimeException) {
                reportUnavailable(this)
                return false
            }
        }

        fun setActive(active: Boolean) = runWhenSessionUsable {
            session.setActive(active)
        }

        fun setFocused(focused: Boolean) = runWhenSessionUsable {
            session.setFocused(focused)
        }

        fun close() {
            if (!crashRecovery.canUseSession) return
            runCatching {
                session.setFocused(false)
                session.setActive(false)
                session.stop()
                session.close()
            }.onFailure { crashRecovery.markClosedByContentProcess() }
        }

        private fun runWhenSessionUsable(operation: () -> Unit) {
            if (!crashRecovery.canUseSession) return
            runCatching(operation).onFailure {
                crashRecovery.markClosedByContentProcess()
                reportUnavailable(this)
            }
        }
    }

    private val photosSession = ManagedSession(listOf(photosUrl), "Photos")
    private val homeAssistantUrl = homeAssistantUrl
    private val homeAssistantFallbackUrl = homeAssistantFallbackUrl
    private var homeAssistantSession: ManagedSession? = null
    private var cameraSession: ManagedSession? = null
    private var attachedSlot: FrameWebSlot? = null
    private var geckoAttachedSession: ManagedSession? = null
    private val hiddenHomeLifecycle = FrameWebHiddenSessionLifecycle()
    private var preloadDeactivation: Runnable? = null
    private val evictHiddenHome = Runnable { evictHiddenHomeIfExpired() }
    private val retryCallbacks = mutableMapOf<ManagedSession, Runnable>()

    init {
        setBackgroundColor(Color.BLACK)
        coverUntilFirstPaint(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = View.GONE
    }

    fun preload(slot: FrameWebSlot, url: String) {
        require(slot.canPreload) { "$slot cannot be preloaded" }
        val managed = sessionFor(slot)
        val generation = hiddenHomeLifecycle.onHomePreloaded(SystemClock.uptimeMillis())
        scheduleHiddenHomeEviction()
        if (!managed.loadState.shouldRequest(url)) return
        preloadDeactivation?.let(::removeCallbacks)
        managed.setActive(true)
        managed.request(url)
        val deactivate = Runnable {
            if (hiddenHomeLifecycle.isCurrentPreload(generation) && homeAssistantSession === managed && attachedSlot != slot) {
                managed.setActive(false)
            }
        }
        preloadDeactivation = deactivate
        postDelayed(deactivate, FrameWebPreloadPolicy.CALENDAR_ACTIVE_MILLIS)
    }

    fun show(slot: FrameWebSlot, url: String, takeFocus: Boolean) {
        if (slot == FrameWebSlot.HOME_ASSISTANT) {
            removeCallbacks(evictHiddenHome)
            hiddenHomeLifecycle.onHomeShown()
        } else {
            hiddenHomeLifecycle.onNonHomeShown()
        }
        if (attachedSlot == FrameWebSlot.CAMERAS && slot != FrameWebSlot.CAMERAS) {
            disposeCameraSession()
        }
        val managed = sessionFor(slot)
        allSessions().filter { it !== managed }.forEach {
            cancelRecovery(it)
            it.setActive(false)
            it.setFocused(false)
        }
        if (attachedSlot != slot) {
            if (session != null) releaseAttachedSession()
            setSession(managed.session)
            geckoAttachedSession = managed
            attachedSlot = slot
        }
        visibility = View.VISIBLE
        managed.setActive(true)
        if (managed.loadState.shouldRequest(url)) {
            cancelRecovery(managed)
            managed.recoveryAttempts = 0
            managed.request(url)
        }
        managed.setFocused(takeFocus)
        if (takeFocus) requestFocus()
        contentDescription = managed.label
        if (slot != FrameWebSlot.HOME_ASSISTANT) scheduleHiddenHomeEviction()
    }

    fun hide() {
        visibility = View.GONE
        if (attachedSlot == FrameWebSlot.CAMERAS) disposeCameraSession()
        allSessions().forEach {
            cancelRecovery(it)
            it.setFocused(false)
            it.setActive(false)
        }
        if (attachedSlot == FrameWebSlot.HOME_ASSISTANT && session != null) {
            releaseAttachedSession()
            attachedSlot = null
        }
        scheduleHiddenHomeEviction()
    }

    fun setContentActive(active: Boolean) {
        if (!active && attachedSlot == FrameWebSlot.CAMERAS) {
            disposeCameraSession()
            return
        }
        attachedSlot?.let { slot -> sessionFor(slot).setActive(active) }
    }

    fun movePhoto(forward: Boolean): Boolean {
        if (attachedSlot != FrameWebSlot.PHOTOS || visibility != View.VISIBLE || width <= 0 || height <= 0) {
            return false
        }
        val eventTime = SystemClock.uptimeMillis()
        val x = width * if (forward) 0.75f else 0.25f
        val y = height * 0.50f
        val down = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(eventTime, eventTime + PHOTO_TAP_MILLIS, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            dispatchTouchEvent(down)
            dispatchTouchEvent(up)
            true
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun destroy() {
        removeCallbacks(evictHiddenHome)
        preloadDeactivation?.let(::removeCallbacks)
        preloadDeactivation = null
        retryCallbacks.values.forEach(::removeCallbacks)
        retryCallbacks.clear()
        hiddenHomeLifecycle.onEvicted()
        if (session != null) releaseAttachedSession()
        cameraSession?.close()
        cameraSession = null
        homeAssistantSession?.close()
        homeAssistantSession = null
        photosSession.close()
    }

    fun trimMemory(level: Int) {
        if (retentionPolicy.shouldEvictForTrimMemory(level)) evictHiddenHome()
    }

    private fun sessionFor(slot: FrameWebSlot): ManagedSession = when (slot) {
        FrameWebSlot.PHOTOS -> photosSession
        FrameWebSlot.HOME_ASSISTANT -> homeAssistantSession ?: ManagedSession(homeAssistantOrigins(), "Home Assistant")
            .also { homeAssistantSession = it }
        FrameWebSlot.CAMERAS -> cameraSession ?: ManagedSession(
            configuredUrls = homeAssistantOrigins(),
            label = "Cameras",
        ).also { cameraSession = it }
    }

    private fun homeAssistantOrigins(): List<String> = buildList {
        add(homeAssistantUrl)
        homeAssistantFallbackUrl?.let(::add)
    }

    private fun allSessions(): List<ManagedSession> = buildList {
        add(photosSession)
        homeAssistantSession?.let(::add)
        cameraSession?.let(::add)
    }

    private fun disposeCameraSession() {
        val disposable = cameraSession ?: return
        if (attachedSlot == FrameWebSlot.CAMERAS) {
            if (session != null) releaseAttachedSession()
            attachedSlot = null
        }
        disposable.close()
        cancelRecovery(disposable)
        cameraSession = null
    }

    private fun scheduleHiddenHomeEviction() {
        homeAssistantSession ?: return
        if (attachedSlot == FrameWebSlot.HOME_ASSISTANT) return
        hiddenHomeLifecycle.onHomeHidden(SystemClock.uptimeMillis())
        val hiddenSince = requireNotNull(hiddenHomeLifecycle.hiddenSinceMillis)
        removeCallbacks(evictHiddenHome)
        postDelayed(evictHiddenHome, (FrameWebSessionRetentionPolicy.HIDDEN_HOME_TTL_MILLIS - (SystemClock.uptimeMillis() - hiddenSince)).coerceAtLeast(0L))
    }

    private fun evictHiddenHomeIfExpired() {
        val hiddenSince = hiddenHomeLifecycle.hiddenSinceMillis ?: return
        if (retentionPolicy.shouldEvictHiddenHome(SystemClock.uptimeMillis(), hiddenSince)) evictHiddenHome()
    }

    private fun evictHiddenHome() {
        if (attachedSlot == FrameWebSlot.HOME_ASSISTANT) return
        removeCallbacks(evictHiddenHome)
        preloadDeactivation?.let(::removeCallbacks)
        preloadDeactivation = null
        hiddenHomeLifecycle.onEvicted()
        homeAssistantSession?.close()
        homeAssistantSession?.let(::cancelRecovery)
        homeAssistantSession = null
    }

    private fun reportUnavailable(managed: ManagedSession) {
        managed.loadState.recordFailure()
        listener.onPageUnavailable(managed.label)
        if (managed.retryPending || visibility != View.VISIBLE || managed !== attachedManagedSession()) return
        scheduleRecovery(managed)
    }

    private fun scheduleRecovery(managed: ManagedSession) {
        val url = fallbackUrlFor(managed.loadState.recoveryUrl()) ?: return
        val retry = recoveryPolicy.nextRetry(managed.recoveryAttempts)
        managed.recoveryAttempts = retry.attempt
        managed.retryPending = true
        listener.onPageRecovery(managed.label, retry.attempt, retry.delayMillis)
        val callback = Runnable {
            retryCallbacks.remove(managed)
            managed.retryPending = false
            if (visibility != View.VISIBLE || managed !== attachedManagedSession()) return@Runnable
            managed.request(url)
        }
        retryCallbacks[managed] = callback
        postDelayed(callback, retry.delayMillis)
    }

    private fun resetRecovery(managed: ManagedSession) {
        managed.recoveryAttempts = 0
        managed.retryPending = false
        cancelRecovery(managed)
    }

    private fun cancelRecovery(managed: ManagedSession) {
        retryCallbacks.remove(managed)?.let(::removeCallbacks)
        managed.retryPending = false
    }

    private fun releaseAttachedSession() {
        if (geckoAttachedSession?.crashRecovery?.canUseSession == false) return
        runCatching(::releaseSession).onSuccess { geckoAttachedSession = null }
    }

    private fun attachedManagedSession(): ManagedSession? = attachedSlot?.let(::sessionFor)

    private fun fallbackUrlFor(url: String?): String? {
        if (url == null) return null
        val fallback = homeAssistantFallbackUrl?.let { value -> runCatching { URI(value) }.getOrNull() } ?: return url
        val requested = runCatching { URI(url) }.getOrNull() ?: return url
        val primary = runCatching { URI(homeAssistantUrl) }.getOrNull() ?: return url
        if (!sameOrigin(requested, primary)) return url
        return URI(
            fallback.scheme,
            null,
            fallback.host,
            fallback.port,
            requested.rawPath,
            requested.rawQuery,
            requested.rawFragment,
        ).toASCIIString()
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private companion object {
        const val PHOTO_TAP_MILLIS = 36L
    }
}
