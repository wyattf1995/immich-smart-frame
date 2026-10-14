package com.wyattfleming.touchframe.media

import android.os.Handler
import android.os.Looper
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import org.json.JSONObject
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Gives the packaged live-camera page a single-use capability, never an OAuth
 * token in a URL or durable browser state. GeckoView exposes a session only for
 * page/content senders, so a missing session is always rejected for the
 * sensitive start request. The global extension delegate is used solely to
 * learn the packaged page URL from this exact installed extension instance.
 */
internal class TouchLiveCameraBridge(
    origin: String,
    cameraIds: List<String>,
    private val tokenProvider: (WeatherRequestDeadline) -> String?,
    private val onStatus: (Status) -> Unit = {},
) : AutoCloseable {
    enum class Status { READY, CONNECTING, PLAYING, FAILED, STOPPED }

    private val policy = TouchLiveCameraPolicy(origin, cameraIds)
    private val capability = TouchLiveCameraCapabilityGate()
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "touch-live-camera-token").apply { isDaemon = true }
    }
    private val random = SecureRandom()
    private var extension: WebExtension? = null
    private var activeSession: GeckoSession? = null
    private var extensionPageUrl: String? = null
    private var visible = false
    private var closed = false

    /** Installs the trusted built-in extension and binds this bridge to one owned Gecko session. */
    fun attach(runtime: GeckoRuntime, session: GeckoSession, onPageReady: (String) -> Unit): Boolean {
        if (closed || activeSession != null) return false
        activeSession = session
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept({ installed ->
            main.post {
                if (closed || activeSession !== session || installed == null) return@post
                extension = installed
                // The global delegate is extension-instance scoped. It handles only
                // the non-sensitive packaged-page URL announcement below.
                installed.setMessageDelegate(delegate, NATIVE_APP)
                session.webExtensionController.setMessageDelegate(installed, delegate, NATIVE_APP)
                extensionPageUrl?.let(onPageReady)
            }
        }, {
            main.post { if (!closed && activeSession === session) onStatus(Status.FAILED) }
        })
        return true
    }

    /** Loads the packaged extension page with a native-only, one-time capability nonce. */
    fun start(camera: String): Boolean {
        val session = activeSession ?: return false
        val page = extensionPageUrl ?: return false
        if (closed || !visible || !policy.allowsCamera(camera)) return false
        capability.revoke()
        val next = capability.begin(camera, nonce())
        onStatus(Status.CONNECTING)
        session.loadUri("$page#capability=${next.nonce}&entity=${next.entityId}")
        return true
    }

    /** Revokes the capability before a hidden surface can receive or retain a fresh token. */
    fun setVisible(nextVisible: Boolean) {
        visible = nextVisible && !closed
        if (!visible) {
            capability.revoke()
            onStatus(Status.STOPPED)
        }
    }

    fun stop() = setVisible(false)

    override fun close() {
        if (closed) return
        closed = true
        visible = false
        capability.revoke()
        val installed = extension
        activeSession?.let { session ->
            if (installed != null) session.webExtensionController.setMessageDelegate(installed, null, NATIVE_APP)
        }
        installed?.setMessageDelegate(null, NATIVE_APP)
        activeSession = null
        extension = null
        extensionPageUrl = null
        worker.shutdownNow()
        onStatus(Status.STOPPED)
    }

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            if (nativeApp != NATIVE_APP || message !is JSONObject) return GeckoResult.fromValue(response(false))
            return when (message.optString("type")) {
                PAGE_ANNOUNCEMENT -> GeckoResult.fromValue(acceptPageAnnouncement(message, sender))
                START_REQUEST -> grantStart(message, sender)
                STATUS_EVENT -> GeckoResult.fromValue(acceptStatus(message, sender))
                else -> GeckoResult.fromValue(response(false))
            }
        }
    }

    /** The extension background may announce only its own static live.html URL; it never receives a token. */
    private fun acceptPageAnnouncement(message: JSONObject, sender: WebExtension.MessageSender): GeckoBundle {
        if (sender.session != null) return response(false)
        val value = message.optString("url")
        if (!policy.isTrustedPageUrl(value)) return response(false)
        extensionPageUrl = value
        onStatus(Status.READY)
        return response(true)
    }

    private fun grantStart(message: JSONObject, sender: WebExtension.MessageSender): GeckoResult<Any> {
        val current = capability.current() ?: return GeckoResult.fromValue(response(false))
        if (!trustedLivePage(sender) || !capability.consume(
                current.generation,
                message.optString("entity_id"),
                message.optString("capability"),
                visible,
            )
        ) return GeckoResult.fromValue(response(false))
        val result = GeckoResult<Any>()
        worker.execute {
            val deadline = WeatherRequestDeadline(timeoutMillis = START_TIMEOUT_MILLIS)
            val token = tokenProvider(deadline)
            // The token is held only in this worker stack and the returned bundle.
            if (closed || !visible || deadline.isExpired() || token.isNullOrBlank()) {
                result.complete(response(false))
            } else {
                result.complete(GeckoBundle(4).apply {
                    putBoolean("accepted", true)
                    putString("entity_id", current.entityId)
                    putString("origin", policy.websocketUrl.toString())
                    putString("access_token", token)
                })
            }
        }
        return result
    }

    private fun acceptStatus(message: JSONObject, sender: WebExtension.MessageSender): GeckoBundle {
        val current = capability.current() ?: return response(false)
        if (!trustedLivePage(sender) || message.optString("entity_id") != current.entityId || message.optString("capability") != current.nonce) return response(false)
        when (message.optString("state")) {
            "playing" -> onStatus(Status.PLAYING)
            "failed" -> onStatus(Status.FAILED)
            else -> return response(false)
        }
        return response(true)
    }

    /** Enforces every sender field GeckoView makes available; missing session is never tolerated for token issuance. */
    private fun trustedLivePage(sender: WebExtension.MessageSender): Boolean {
        val expected = extensionPageUrl ?: return false
        val senderSession = sender.session ?: return false
        return senderSession === activeSession && sender.isTopLevel && stripFragment(sender.url) == expected && policy.isTrustedPageUrl(expected)
    }

    private fun nonce(): String = ByteArray(24).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun stripFragment(value: String): String = runCatching {
        URI(value).let { URI(it.scheme, it.authority, it.path, it.query, null).toString() }
    }.getOrDefault("")

    private fun response(accepted: Boolean): GeckoBundle = GeckoBundle(1).apply { putBoolean("accepted", accepted) }

    private companion object {
        const val EXTENSION_LOCATION = "resource://android/assets/frame-live-camera/"
        const val EXTENSION_ID = "frame-live-camera@wyattfleming.com"
        const val NATIVE_APP = "frame_live_camera"
        const val PAGE_ANNOUNCEMENT = "live-extension-page"
        const val START_REQUEST = "start-live"
        const val STATUS_EVENT = "live-status"
        const val START_TIMEOUT_MILLIS = 12_000L
    }
}
