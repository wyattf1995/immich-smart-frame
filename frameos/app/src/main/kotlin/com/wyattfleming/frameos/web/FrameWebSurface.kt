package com.wyattfleming.frameos.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import com.wyattfleming.frameos.FrameOperationLogEntry
import com.wyattfleming.frameos.FrameOperationLogger
import com.wyattfleming.frameos.NoOpFrameOperationLogger
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
    birdsUrl: String? = null,
    private val warmHomeAssistantView: GeckoView,
    private val listener: Listener,
    private val urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
    private val operationLogger: FrameOperationLogger = NoOpFrameOperationLogger,
    private val elapsedRealtime: () -> Long = { SystemClock.uptimeMillis() },
    private val pageLoadWatchdogMillis: Long = PAGE_LOAD_WATCHDOG_MILLIS,
) : GeckoView(context) {
    interface Listener {
        fun onPageLoading(label: String, loading: Boolean)
        fun onPageUnavailable(label: String)
        fun onPageRecovery(label: String, attempt: Int, retryInMillis: Long)
        fun onPageRecovered(label: String)
        fun onPageRenderInvalidated(label: String)
        fun onPageRendered(label: String)
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
        val renderedState = FrameWebRenderedState()
        val crashRecovery = FrameWebCrashRecoveryState()
        var recoveryAttempts = 0
        var retryPending = false
        var lifecycleSuspended = false
        private val pageLoadWatchdogState = FrameWebPageLoadWatchdogState()
        private var pageLoadWatchdog: Runnable? = null

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
                    renderedState.recordPageStart()
                    listener.onPageRenderInvalidated(label)
                    armPageLoadWatchdog(session)
                    listener.onPageLoading(label, true)
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    listener.onPageLoading(label, false)
                    if (!success) {
                        val pageLoad = disarmPageLoadWatchdog()
                        renderedState.recordFailure()
                        if (!pageLoad.timedOut) reportUnavailable(this@ManagedSession)
                    } else {
                        val newlyRendered = renderedState.recordPageStop(success = true)
                        maybeReportRendered(newlyRendered)
                    }
                }
            }
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onFirstContentfulPaint(session: GeckoSession) {
                    val newlyRendered = renderedState.recordFirstContentfulPaint()
                    maybeReportRendered(newlyRendered)
                }

                override fun onPaintStatusReset(session: GeckoSession) {
                    val invalidatedRenderedContent = renderedState.recordPaintStatusReset()
                    listener.onPageRenderInvalidated(label)
                    if (!invalidatedRenderedContent) return
                    if (
                        lifecycleSuspended ||
                        this@ManagedSession !== displayedManagedSession() ||
                        !displayedSurfaceVisible()
                    ) {
                        loadState.recordFailure()
                        return
                    }
                    armPageLoadWatchdog(session)
                }

                override fun onCrash(session: GeckoSession) {
                    disarmPageLoadWatchdog()
                    crashRecovery.markClosedByContentProcess()
                    reportUnavailable(this@ManagedSession)
                }

                override fun onKill(session: GeckoSession) {
                    disarmPageLoadWatchdog()
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
            lifecycleSuspended = false
            loadState.recordRequest(url)
            renderedState.recordRequest()
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

        private fun maybeReportRendered(newlyRendered: Boolean) {
            if (!newlyRendered) return
            val pageLoad = disarmPageLoadWatchdog()
            if (pageLoad.timedOut) {
                renderedState.recordFailure()
                return
            }
            loadState.recordSuccess()
            resetRecovery(this)
            listener.onPageRecovered(label)
            this@FrameWebSurface.reportRenderedIfDisplayed(this)
        }

        fun setActive(active: Boolean) {
            if (active) {
                lifecycleSuspended = false
            } else {
                lifecycleSuspended = true
                stopWatchingDisplayedContent()
            }
            runWhenSessionUsable { session.setActive(active) }
        }

        fun stopWatchingDisplayedContent() {
            val pageLoad = disarmPageLoadWatchdog()
            if (pageLoad.hadActiveLoad && !renderedState.rendered) {
                loadState.recordFailure()
            }
        }

        fun setFocused(focused: Boolean) = runWhenSessionUsable {
            session.setFocused(focused)
        }

        fun close() {
            prepareForDisposal()
            runCatching { session.close() }
                .onFailure { crashRecovery.markClosedByContentProcess() }
        }

        fun prepareForDisposal() {
            lifecycleSuspended = true
            disarmPageLoadWatchdog()
            if (!crashRecovery.canUseSession) return
            val clean = FrameWebSessionDisposal.prepare(
                clearFocus = { session.setFocused(false) },
                deactivate = { session.setActive(false) },
                stop = { session.stop() },
            )
            if (!clean) crashRecovery.markClosedByContentProcess()
        }

        fun suspendForLifecycle() {
            lifecycleSuspended = true
            val pageLoad = disarmPageLoadWatchdog()
            if (pageLoad.hadActiveLoad) {
                loadState.recordFailure()
                runCatching { session.stop() }
            }
            if (!crashRecovery.canUseSession) return
            runCatching {
                session.setFocused(false)
                session.setActive(false)
            }.onFailure { crashRecovery.markClosedByContentProcess() }
        }

        private fun runWhenSessionUsable(operation: () -> Unit) {
            if (!crashRecovery.canUseSession) return
            runCatching(operation).onFailure {
                crashRecovery.markClosedByContentProcess()
                reportUnavailable(this)
            }
        }

        private fun armPageLoadWatchdog(observedSession: GeckoSession) {
            pageLoadWatchdog?.let(this@FrameWebSurface::removeCallbacks)
            pageLoadWatchdog = null
            val load = pageLoadWatchdogState.arm(elapsedRealtime())
            val watchdog = Runnable {
                if (session !== observedSession) return@Runnable
                val startedAtMillis = pageLoadWatchdogState.markTimedOut(load.generation) ?: return@Runnable
                pageLoadWatchdog = null
                operationLogger.log(
                    FrameOperationLogEntry(
                        route = label,
                        stage = "page_load",
                        outcome = "timeout",
                        elapsedMillis = (elapsedRealtime() - startedAtMillis).coerceAtLeast(0L),
                    ),
                )
                runCatching { observedSession.stop() }
                reportUnavailable(this)
            }
            pageLoadWatchdog = watchdog
            this@FrameWebSurface.postDelayed(watchdog, pageLoadWatchdogMillis)
        }

        private fun disarmPageLoadWatchdog(): FrameWebPageLoadDisarm {
            pageLoadWatchdog?.let(this@FrameWebSurface::removeCallbacks)
            pageLoadWatchdog = null
            return pageLoadWatchdogState.disarm()
        }
    }

    private val photosSession = ManagedSession(listOf(photosUrl), "Photos")
    private val homeAssistantUrl = homeAssistantUrl
    private val homeAssistantFallbackUrl = homeAssistantFallbackUrl
    private val birdsUrl = birdsUrl
    private var homeAssistantSession: ManagedSession? = null
    private var cameraSession: ManagedSession? = null
    private var birdsSession: ManagedSession? = null
    private var foregroundSlot: FrameWebSlot? = null
    private var displayedSlot: FrameWebSlot? = null
    private var foregroundAttachedSession: ManagedSession? = null
    private var warmHomeAttachedSession: ManagedSession? = null
    private val hiddenHomeLifecycle = FrameWebHiddenSessionLifecycle()
    private var preloadDeactivation: Runnable? = null
    private val evictHiddenHome = Runnable { evictHiddenHomeIfExpired() }
    private val retryCallbacks = mutableMapOf<ManagedSession, Runnable>()
    private val pendingCameraClosures = mutableMapOf<ManagedSession, Runnable>()

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
        managed.lifecycleSuspended = false
        ensureWarmHomeAttached(managed)
        warmHomeAssistantView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        managed.setFocused(false)
        val generation = hiddenHomeLifecycle.onHomePreloaded(SystemClock.uptimeMillis())
        scheduleHiddenHomeEviction()
        preloadDeactivation?.let(::removeCallbacks)
        managed.setActive(true)
        if (managed.loadState.shouldRequest(url)) managed.request(url)
        val deactivate = Runnable {
            if (
                hiddenHomeLifecycle.isCurrentPreload(generation) &&
                homeAssistantSession === managed &&
                displayedSlot != slot
            ) {
                managed.setActive(false)
            }
        }
        preloadDeactivation = deactivate
        postDelayed(deactivate, FrameWebPreloadPolicy.CALENDAR_ACTIVE_MILLIS)
    }

    fun show(slot: FrameWebSlot, url: String, takeFocus: Boolean) {
        if (displayedSlot != null && displayedSlot != slot) {
            displayedManagedSession()?.stopWatchingDisplayedContent()
        }
        if (slot == FrameWebSlot.HOME_ASSISTANT) {
            removeCallbacks(evictHiddenHome)
            hiddenHomeLifecycle.onHomeShown()
        } else {
            hiddenHomeLifecycle.onNonHomeShown()
        }
        if (displayedSlot == FrameWebSlot.CAMERAS && slot != FrameWebSlot.CAMERAS) {
            disposeCameraSession()
        }
        if (displayedSlot == FrameWebSlot.BIRDS && slot != FrameWebSlot.BIRDS) {
            disposeBirdsSession()
        }
        val managed = sessionFor(slot)
        managed.lifecycleSuspended = false
        allSessions().filter { it !== managed && it !== homeAssistantSession }.forEach {
            cancelRecovery(it)
            it.setActive(false)
            it.setFocused(false)
        }
        homeAssistantSession?.setFocused(false)
        if (slot == FrameWebSlot.HOME_ASSISTANT) {
            preloadDeactivation?.let(::removeCallbacks)
            preloadDeactivation = null
            ensureWarmHomeAttached(managed)
            visibility = View.GONE
            warmHomeAssistantView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            attachForeground(slot, managed)
            visibility = View.VISIBLE
            warmHomeAssistantView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        displayedSlot = slot
        managed.setActive(true)
        if (managed.loadState.shouldRequest(url)) {
            cancelRecovery(managed)
            managed.recoveryAttempts = 0
            managed.request(url)
        }
        managed.setFocused(takeFocus)
        val displayedView = if (slot == FrameWebSlot.HOME_ASSISTANT) warmHomeAssistantView else this
        if (takeFocus) displayedView.requestFocus()
        displayedView.contentDescription = managed.label
        if (slot != FrameWebSlot.HOME_ASSISTANT) scheduleHiddenHomeEviction()
        reportRenderedIfDisplayed(managed)
    }

    fun hide() {
        displayedManagedSession()?.stopWatchingDisplayedContent()
        visibility = View.GONE
        if (displayedSlot == FrameWebSlot.CAMERAS) disposeCameraSession()
        if (displayedSlot == FrameWebSlot.BIRDS) disposeBirdsSession()
        allSessions().filter { it !== homeAssistantSession }.forEach {
            cancelRecovery(it)
            it.setFocused(false)
            it.setActive(false)
        }
        homeAssistantSession?.setFocused(false)
        warmHomeAssistantView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        displayedSlot = null
        scheduleHiddenHomeEviction()
    }

    fun setContentActive(active: Boolean) {
        if (!active && displayedSlot == FrameWebSlot.CAMERAS) {
            disposeCameraSession()
            return
        }
        if (!active && displayedSlot == FrameWebSlot.BIRDS) {
            disposeBirdsSession()
            return
        }
        displayedSlot?.let { slot ->
            val managed = sessionFor(slot)
            if (active) managed.lifecycleSuspended = false
            managed.setActive(active)
        }
    }

    fun isDisplayingRenderedLabel(label: String): Boolean {
        val managed = displayedManagedSession() ?: return false
        return managed.label == label && managed.renderedState.rendered && displayedSurfaceVisible()
    }

    fun retryDisplayedContentIfUnrendered(): Boolean {
        val managed = displayedManagedSession() ?: return false
        if (managed.renderedState.rendered) return false
        val url = managed.loadState.recoveryUrl() ?: return false
        cancelRecovery(managed)
        return managed.request(url)
    }

    fun suspendAllContent() {
        if (displayedSlot == FrameWebSlot.CAMERAS) disposeCameraSession()
        if (displayedSlot == FrameWebSlot.BIRDS) disposeBirdsSession()
        allSessions().forEach {
            cancelRecovery(it)
            it.suspendForLifecycle()
        }
    }

    fun forwardKey(event: KeyEvent): Boolean {
        val displayedView = when (displayedSlot) {
            FrameWebSlot.HOME_ASSISTANT -> warmHomeAssistantView
            FrameWebSlot.PHOTOS, FrameWebSlot.CAMERAS, FrameWebSlot.BIRDS -> this
            null -> return false
        }
        displayedView.requestFocus()
        return displayedView.dispatchKeyEvent(event)
    }

    fun movePhoto(forward: Boolean): Boolean {
        if (displayedSlot != FrameWebSlot.PHOTOS || visibility != View.VISIBLE || width <= 0 || height <= 0) {
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
        pendingCameraClosures.forEach { (managed, callback) ->
            removeCallbacks(callback)
            managed.close()
        }
        pendingCameraClosures.clear()
        hiddenHomeLifecycle.onEvicted()
        if (session != null) releaseForegroundAttachedSession()
        releaseWarmHomeAttachedSession()
        cameraSession?.close()
        cameraSession = null
        birdsSession?.close()
        birdsSession = null
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
        FrameWebSlot.BIRDS -> birdsSession ?: ManagedSession(
            configuredUrls = listOf(requireNotNull(birdsUrl)),
            label = "Birds",
        ).also { birdsSession = it }
    }

    private fun homeAssistantOrigins(): List<String> = buildList {
        add(homeAssistantUrl)
        homeAssistantFallbackUrl?.let(::add)
    }

    private fun allSessions(): List<ManagedSession> = buildList {
        add(photosSession)
        homeAssistantSession?.let(::add)
        cameraSession?.let(::add)
        birdsSession?.let(::add)
    }

    private fun attachForeground(slot: FrameWebSlot, managed: ManagedSession) {
        require(slot != FrameWebSlot.HOME_ASSISTANT) { "Home Assistant uses its persistent warm surface" }
        if (foregroundSlot == slot && foregroundAttachedSession === managed) return
        if (session != null) releaseForegroundAttachedSession()
        setSession(managed.session)
        foregroundAttachedSession = managed
        foregroundSlot = slot
    }

    private fun ensureWarmHomeAttached(managed: ManagedSession) {
        require(managed === homeAssistantSession) { "Only the persistent Home Assistant session can use the warm surface" }
        if (warmHomeAttachedSession !== managed) {
            if (warmHomeAssistantView.session != null) releaseWarmHomeAttachedSession()
            warmHomeAssistantView.setSession(managed.session)
            warmHomeAttachedSession = managed
        }
        warmHomeAssistantView.visibility = View.VISIBLE
    }

    private fun disposeCameraSession() {
        val disposable = cameraSession ?: return
        if (foregroundSlot == FrameWebSlot.CAMERAS) {
            if (session != null) releaseForegroundAttachedSession()
            foregroundSlot = null
        }
        if (displayedSlot == FrameWebSlot.CAMERAS) displayedSlot = null
        cancelRecovery(disposable)
        cameraSession = null
        val close = Runnable {
            pendingCameraClosures.remove(disposable)
            disposable.close()
        }
        pendingCameraClosures[disposable] = close
        post(close)
    }

    private fun disposeBirdsSession() {
        val disposable = birdsSession ?: return
        cancelRecovery(disposable)
        disposable.prepareForDisposal()
        if (foregroundSlot == FrameWebSlot.BIRDS) {
            if (session != null) releaseForegroundAttachedSession()
            foregroundSlot = null
        }
        if (displayedSlot == FrameWebSlot.BIRDS) displayedSlot = null
        birdsSession = null
        val close = Runnable {
            pendingCameraClosures.remove(disposable)
            disposable.close()
        }
        pendingCameraClosures[disposable] = close
        post(close)
    }

    private fun scheduleHiddenHomeEviction() {
        homeAssistantSession ?: return
        if (displayedSlot == FrameWebSlot.HOME_ASSISTANT) return
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
        if (displayedSlot == FrameWebSlot.HOME_ASSISTANT) return
        removeCallbacks(evictHiddenHome)
        preloadDeactivation?.let(::removeCallbacks)
        preloadDeactivation = null
        hiddenHomeLifecycle.onEvicted()
        releaseWarmHomeAttachedSession()
        homeAssistantSession?.close()
        homeAssistantSession?.let(::cancelRecovery)
        homeAssistantSession = null
    }

    private fun reportUnavailable(managed: ManagedSession) {
        managed.renderedState.recordFailure()
        managed.loadState.recordFailure()
        if (managed.lifecycleSuspended) {
            cancelRecovery(managed)
            return
        }
        listener.onPageUnavailable(managed.label)
        if (managed.retryPending || !displayedSurfaceVisible() || managed !== displayedManagedSession()) return
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
            if (managed.lifecycleSuspended || !displayedSurfaceVisible() || managed !== displayedManagedSession()) return@Runnable
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

    private fun releaseForegroundAttachedSession() {
        if (session != null) runCatching(::releaseSession)
        foregroundAttachedSession = null
        foregroundSlot = null
    }

    private fun releaseWarmHomeAttachedSession() {
        if (warmHomeAssistantView.session != null) {
            runCatching(warmHomeAssistantView::releaseSession)
        }
        warmHomeAttachedSession = null
        warmHomeAssistantView.visibility = View.GONE
    }

    private fun displayedManagedSession(): ManagedSession? = displayedSlot?.let(::sessionFor)

    private fun reportRenderedIfDisplayed(managed: ManagedSession) {
        if (
            managed.renderedState.rendered &&
            managed === displayedManagedSession() &&
            displayedSurfaceVisible()
        ) {
            listener.onPageRendered(managed.label)
        }
    }

    private fun displayedSurfaceVisible(): Boolean = when (displayedSlot) {
        FrameWebSlot.HOME_ASSISTANT -> warmHomeAssistantView.visibility == View.VISIBLE
        FrameWebSlot.PHOTOS, FrameWebSlot.CAMERAS, FrameWebSlot.BIRDS -> visibility == View.VISIBLE
        null -> false
    }

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
        const val PAGE_LOAD_WATCHDOG_MILLIS = 20_000L
    }
}
