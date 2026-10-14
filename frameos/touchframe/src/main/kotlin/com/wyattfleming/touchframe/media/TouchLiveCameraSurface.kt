package com.wyattfleming.touchframe.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.net.URI

/** A short-lived, single-camera Gecko surface. It never loads a remote document. */
internal class TouchLiveCameraSurface(
    context: Context,
    runtime: GeckoRuntime,
    origin: String,
    cameraIds: List<String>,
    tokenProvider: (com.wyattfleming.frameos.weather.WeatherRequestDeadline) -> String?,
    private val onStatus: (TouchLiveCameraBridge.Status) -> Unit = {},
) : FrameLayout(context), AutoCloseable {
    private val geckoView = GeckoView(context).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW) }
    private val session = GeckoSession(GeckoSessionSettings().apply {
        setDisplayMode(GeckoSessionSettings.DISPLAY_MODE_FULLSCREEN)
    })
    private var expectedPage: String? = null
    private var requestedCamera: String? = null
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val bridge = TouchLiveCameraBridge(origin, cameraIds, tokenProvider) { status ->
        main.post {
            onStatus(status)
            if (status == TouchLiveCameraBridge.Status.FAILED) visibility = View.GONE
        }
    }

    init {
        addView(geckoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                source: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(
                if (source === session && allowedUri(request.uri)) AllowOrDeny.ALLOW else AllowOrDeny.DENY,
            )

            override fun onNewSession(source: GeckoSession, uri: String): GeckoResult<GeckoSession>? = null
        }
        session.open(runtime)
        geckoView.setSession(session)
        bridge.attach(runtime, session) { page ->
            if (closed) return@attach
            expectedPage = page
            requestedCamera?.let { bridge.start(it) }
        }
        visibility = View.GONE
    }

    fun start(camera: String): Boolean {
        if (closed) return false
        requestedCamera = camera
        visibility = View.VISIBLE
        bridge.setVisible(true)
        return bridge.start(camera)
    }

    fun hideLive() {
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        requestedCamera = null
        bridge.close()
        geckoView.releaseSession()
        session.close()
    }

    private fun allowedUri(value: String): Boolean {
        val expected = expectedPage ?: return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val base = URI(uri.scheme, uri.authority, uri.path, uri.query, null).toString()
        // Capability fragments are allowed only on the static packaged document.
        return base == expected && uri.query == null && uri.fragment?.matches(Regex("capability=[A-Za-z0-9_-]{16,}&entity=camera\\.[a-z0-9_]+")) == true
    }
}
