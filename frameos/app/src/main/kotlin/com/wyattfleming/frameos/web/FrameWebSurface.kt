package com.wyattfleming.frameos.web

import android.annotation.SuppressLint
import android.content.Context
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

    private inner class ManagedSession(
        val configuredUrl: String,
        val label: String,
    ) {
        val session = GeckoSession(
            GeckoSessionSettings().apply {
                setAllowJavascript(true)
                setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
                setFullAccessibilityTree(true)
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
    }

    private val sessions = mapOf(
        FrameWebSlot.PHOTOS to ManagedSession(photosUrl, "Photos"),
        FrameWebSlot.HOME_ASSISTANT to ManagedSession(homeAssistantUrl, "Home Assistant"),
    )
    private var attachedSlot: FrameWebSlot? = null
    private var hiddenGeneration = 0

    init {
        setBackgroundColor(Color.BLACK)
        coverUntilFirstPaint(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = View.GONE
    }

    fun preload(slot: FrameWebSlot, url: String) {
        val managed = sessions.getValue(slot)
        if (!managed.loadState.shouldRequest(url)) return
        managed.request(url)
        managed.session.setActive(false)
    }

    fun show(slot: FrameWebSlot, url: String, takeFocus: Boolean) {
        hiddenGeneration += 1
        val managed = sessions.getValue(slot)
        sessions.filterKeys { it != slot }.values.forEach {
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
    }

    fun hide(restingHomeAssistantUrl: String? = null) {
        val generation = ++hiddenGeneration
        visibility = View.GONE
        sessions.values.forEach { it.session.setFocused(false) }
        val homeAssistant = sessions.getValue(FrameWebSlot.HOME_ASSISTANT)
        if (restingHomeAssistantUrl != null && homeAssistant.loadState.shouldRequest(restingHomeAssistantUrl)) {
            homeAssistant.session.setActive(true)
            homeAssistant.request(restingHomeAssistantUrl)
            postDelayed({
                if (generation == hiddenGeneration && visibility != View.VISIBLE) {
                    sessions.values.forEach { it.session.setActive(false) }
                }
            }, BACKGROUND_ROUTE_SETTLE_MILLIS)
        } else {
            sessions.values.forEach { it.session.setActive(false) }
        }
    }

    fun setContentActive(active: Boolean) {
        attachedSlot?.let { slot -> sessions.getValue(slot).session.setActive(active) }
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
        if (session != null) releaseSession()
        sessions.values.forEach { it.session.close() }
    }

    private companion object {
        const val BACKGROUND_ROUTE_SETTLE_MILLIS = 800L
        const val PHOTO_TAP_MILLIS = 36L
    }
}
