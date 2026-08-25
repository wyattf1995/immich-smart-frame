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

@SuppressLint("ViewConstructor")
class FrameWebSurface(
    context: Context,
    runtime: GeckoRuntime,
    photosUrl: String,
    homeAssistantUrl: String,
    private val listener: Listener,
    private val urlPolicy: FrameUrlPolicy = FrameUrlPolicy(),
) : GeckoView(context) {
    interface Listener {
        fun onPageLoading(label: String, loading: Boolean)
        fun onPageUnavailable(label: String)
    }

    private val frameRuntime = runtime
    private val retentionPolicy = FrameWebSessionRetentionPolicy()
    private val requiresFullAccessibilityTree = context.getSystemService(AccessibilityManager::class.java)
        ?.isTouchExplorationEnabled == true

    private inner class ManagedSession(
        val configuredUrl: String,
        val label: String,
    ) {
        val session = GeckoSession(
            GeckoSessionSettings().apply {
                setAllowJavascript(true)
                setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
                setFullAccessibilityTree(requiresFullAccessibilityTree)
                setSuspendMediaWhenInactive(true)
            },
        )
        val loadState = FrameWebSessionState()

        init {
            session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest,
                ): GeckoResult<AllowOrDeny> {
                    val allowed = urlPolicy.isAllowedTopLevelNavigation(configuredUrl, request.uri)
                    if (!allowed) listener.onPageUnavailable(label)
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
                        loadState.recordFailure()
                        listener.onPageUnavailable(label)
                    }
                }
            }
            session.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    loadState.recordFailure()
                    listener.onPageUnavailable(label)
                }

                override fun onKill(session: GeckoSession) {
                    loadState.recordFailure()
                    listener.onPageUnavailable(label)
                }
            }
            session.open(frameRuntime)
            session.setActive(false)
            session.setFocused(false)
        }

        fun request(url: String) {
            require(urlPolicy.isAllowedTopLevelNavigation(configuredUrl, url)) { "Unsafe $label navigation" }
            loadState.recordRequest(url)
            try {
                session.loadUri(url)
            } catch (error: RuntimeException) {
                loadState.recordFailure()
                throw error
            }
        }

        fun close() {
            session.setFocused(false)
            session.setActive(false)
            session.stop()
            session.close()
        }
    }

    private val photosSession = ManagedSession(photosUrl, "Photos")
    private val homeAssistantUrl = homeAssistantUrl
    private var homeAssistantSession: ManagedSession? = null
    private var cameraSession: ManagedSession? = null
    private var attachedSlot: FrameWebSlot? = null
    private var hiddenGeneration = 0
    private val hiddenHomeLifecycle = FrameWebHiddenSessionLifecycle()
    private var preloadDeactivation: Runnable? = null
    private val evictHiddenHome = Runnable { evictHiddenHomeIfExpired() }

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
        managed.session.setActive(true)
        managed.request(url)
        val deactivate = Runnable {
            if (hiddenHomeLifecycle.isCurrentPreload(generation) && homeAssistantSession === managed && attachedSlot != slot) {
                managed.session.setActive(false)
            }
        }
        preloadDeactivation = deactivate
        postDelayed(deactivate, PRELOAD_ACTIVE_MILLIS)
    }

    fun show(slot: FrameWebSlot, url: String, takeFocus: Boolean) {
        hiddenGeneration += 1
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
            it.session.setActive(false)
            it.session.setFocused(false)
        }
        if (attachedSlot != slot) {
            if (session != null) releaseSession()
            setSession(managed.session)
            attachedSlot = slot
        }
        visibility = View.VISIBLE
        managed.session.setActive(true)
        if (managed.loadState.shouldRequest(url)) managed.request(url)
        managed.session.setFocused(takeFocus)
        if (takeFocus) requestFocus()
        contentDescription = managed.label
        if (slot != FrameWebSlot.HOME_ASSISTANT) scheduleHiddenHomeEviction()
    }

    fun hide() {
        hiddenGeneration += 1
        visibility = View.GONE
        if (attachedSlot == FrameWebSlot.CAMERAS) disposeCameraSession()
        allSessions().forEach {
            it.session.setFocused(false)
            it.session.setActive(false)
        }
        if (attachedSlot == FrameWebSlot.HOME_ASSISTANT && session != null) {
            releaseSession()
            attachedSlot = null
        }
        scheduleHiddenHomeEviction()
    }

    fun setContentActive(active: Boolean) {
        if (!active && attachedSlot == FrameWebSlot.CAMERAS) {
            disposeCameraSession()
            return
        }
        attachedSlot?.let { slot -> sessionFor(slot).session.setActive(active) }
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
        hiddenHomeLifecycle.onEvicted()
        if (session != null) releaseSession()
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
        FrameWebSlot.HOME_ASSISTANT -> homeAssistantSession ?: ManagedSession(homeAssistantUrl, "Home Assistant")
            .also { homeAssistantSession = it }
        FrameWebSlot.CAMERAS -> cameraSession ?: ManagedSession(
            configuredUrl = homeAssistantUrl,
            label = "Cameras",
        ).also { cameraSession = it }
    }

    private fun allSessions(): List<ManagedSession> = buildList {
        add(photosSession)
        homeAssistantSession?.let(::add)
        cameraSession?.let(::add)
    }

    private fun disposeCameraSession() {
        val disposable = cameraSession ?: return
        if (attachedSlot == FrameWebSlot.CAMERAS) {
            if (session != null) releaseSession()
            attachedSlot = null
        }
        disposable.close()
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
        homeAssistantSession = null
    }

    private companion object {
        const val PRELOAD_ACTIVE_MILLIS = 12_000L
        const val PHOTO_TAP_MILLIS = 36L
    }
}
