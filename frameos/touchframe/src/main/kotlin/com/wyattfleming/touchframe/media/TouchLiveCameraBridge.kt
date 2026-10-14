package com.wyattfleming.touchframe.media

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import org.json.JSONObject
import org.mozilla.gecko.util.GeckoBundle
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Gives the packaged live-camera page a single-use capability, never an OAuth
 * token in a URL or durable browser state. The installed extension metadata
 * supplies the only accepted packaged page URL. GeckoView can report an
 * extension-page sender without a session, so that case is bound to this exact
 * installed extension, active session, visible generation, and one-time nonce.
 */
internal class TouchLiveCameraBridge(
    origin: String,
    cameraIds: List<String>,
    private val tokenProvider: (WeatherRequestDeadline) -> String?,
    private val onStatus: (Status) -> Unit = {},
) : AutoCloseable {
    /** Safe device diagnostics: these values deliberately exclude remote details. */
    enum class Status {
        READY, CONNECTING, NATIVE_GRANT, SOCKET, AUTH, CONFIG, OFFER, ANSWER, ICE, PLAYING, FAILED,
        FAILED_NATIVE_GRANT, FAILED_SOCKET, FAILED_AUTH, FAILED_CONFIG, FAILED_OFFER, FAILED_ANSWER, FAILED_ICE, FAILED_TIMEOUT,
        FAILED_ATTACH, STOPPED;

        fun isFailure(): Boolean = this == FAILED || name.startsWith("FAILED_")
    }

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
    private var pageReady: ((String) -> Unit)? = null
    private var visible = false
    private var closed = false

    /** Installs the trusted built-in extension and binds this bridge to one owned Gecko session. */
    fun attach(runtime: GeckoRuntime, session: GeckoSession, onPageReady: (String) -> Unit): Boolean {
        if (closed || activeSession != null) return false
        activeSession = session
        pageReady = onPageReady
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept({ installed ->
            main.post {
                if (closed || activeSession !== session || installed == null) return@post
                extension = installed
                val page = resolveLivePage(installed.metaData.baseUrl)
                    ?: run {
                        reportStatus(Status.FAILED_ATTACH)
                        return@post
                    }
                extensionPageUrl = page
                // This global delegate is scoped to this exact installed extension.
                // It is required because GeckoView can omit sender.session for a
                // packaged extension page. There is no background handshake.
                installed.setMessageDelegate(delegate, NATIVE_APP)
                session.webExtensionController.setMessageDelegate(installed, delegate, NATIVE_APP)
                reportStatus(Status.READY)
                onPageReady(page)
            }
        }, {
            main.post { if (!closed && activeSession === session) reportStatus(Status.FAILED_ATTACH) }
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
        reportStatus(Status.CONNECTING)
        session.loadUri("$page#capability=${next.nonce}&entity=${next.entityId}")
        return true
    }

    /** Revokes the capability before a hidden surface can receive or retain a fresh token. */
    fun setVisible(nextVisible: Boolean) {
        visible = nextVisible && !closed
        if (!visible) {
            capability.revoke()
            reportStatus(Status.STOPPED)
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
        pageReady = null
        // Keep an already accepted token task runnable long enough to complete
        // its GeckoResult with rejection after close; shutdownNow could discard
        // that task and leave the page promise unresolved.
        worker.shutdown()
        reportStatus(Status.STOPPED)
    }

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            if (nativeApp != NATIVE_APP || message !is JSONObject) return GeckoResult.fromValue(response(false))
            return when (message.optString("type")) {
                START_REQUEST -> grantStart(message, sender)
                STATUS_EVENT -> GeckoResult.fromValue(acceptStatus(message, sender))
                else -> GeckoResult.fromValue(response(false))
            }
        }
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
        try {
            worker.execute {
                try {
                    val deadline = WeatherRequestDeadline(timeoutMillis = START_TIMEOUT_MILLIS)
                    val token = tokenProvider(deadline)
                    // The token is held only in this worker stack and the returned bundle.
                    if (closed || deadline.isExpired() || token.isNullOrBlank() || !capability.isCurrent(current.generation, current.entityId, current.nonce, visible)) {
                        result.complete(response(false))
                    } else {
                        result.complete(GeckoBundle(4).apply {
                            putBoolean("accepted", true)
                            putString("entity_id", current.entityId)
                            putString("origin", policy.websocketUrl.toString())
                            putString("access_token", token)
                        })
                    }
                } catch (_: Throwable) {
                    result.complete(response(false))
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            result.complete(response(false))
        }
        return result
    }

    private fun acceptStatus(message: JSONObject, sender: WebExtension.MessageSender): GeckoBundle {
        val current = capability.current() ?: return response(false)
        if (!trustedLivePage(sender) || message.optString("entity_id") != current.entityId || message.optString("capability") != current.nonce) return response(false)
        val status = PAGE_STATUS[message.optString("state")] ?: return response(false)
        reportStatus(status)
        return response(true)
    }

    /**
     * A local extension-page message may not carry a GeckoSession. In that
     * documented fallback, the exact installed-extension delegate plus an active
     * visible nonce binds the message to this one owned page. If a URL is present,
     * it must still be the metadata-derived page. A non-null session must be the
     * active top-level session; every mismatch is rejected.
     */
    private fun trustedLivePage(sender: WebExtension.MessageSender): Boolean {
        val expected = extensionPageUrl ?: return false
        if (sender.webExtension.id != EXTENSION_ID || extension == null || activeSession == null || !policy.isTrustedPageUrl(expected)) return false
        val senderSession = sender.session
        if (senderSession == null) {
            if (sender.url.isNotBlank() && stripFragment(sender.url) != expected) return false
            return true
        }
        return senderSession === activeSession && sender.isTopLevel && stripFragment(sender.url) == expected
    }

    private fun resolveLivePage(baseUrl: String): String? {
        val page = "${baseUrl.trimEnd('/')}/live.html"
        return page.takeIf(policy::isTrustedPageUrl)
    }

    /** Safe device-QA breadcrumb: enum only, never a URL, token, SDP, or remote payload. */
    private fun reportStatus(status: Status) {
        Log.d(LOG_TAG, "state=${status.name}")
        onStatus(status)
    }

    private fun nonce(): String = ByteArray(24).also(random::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun stripFragment(value: String): String = runCatching {
        URI(value).let { URI(it.scheme, it.authority, it.path, it.query, null).toString() }
    }.getOrDefault("")

    private fun response(accepted: Boolean): GeckoBundle = GeckoBundle(1).apply { putBoolean("accepted", accepted) }

    private companion object {
        const val EXTENSION_LOCATION = "resource://android/assets/frame-live-camera/"
        const val EXTENSION_ID = "frame-live-camera@wyattfleming.com"
        const val NATIVE_APP = "frame_live_camera"
        const val START_REQUEST = "start-live"
        const val STATUS_EVENT = "live-status"
        const val START_TIMEOUT_MILLIS = 12_000L
        const val LOG_TAG = "TouchLiveCamera"
        val PAGE_STATUS = mapOf(
            "native-grant" to Status.NATIVE_GRANT,
            "socket" to Status.SOCKET,
            "auth" to Status.AUTH,
            "config" to Status.CONFIG,
            "offer" to Status.OFFER,
            "answer" to Status.ANSWER,
            "ice" to Status.ICE,
            "playing" to Status.PLAYING,
            "failed-native-grant" to Status.FAILED_NATIVE_GRANT,
            "failed-socket" to Status.FAILED_SOCKET,
            "failed-auth" to Status.FAILED_AUTH,
            "failed-config" to Status.FAILED_CONFIG,
            "failed-offer" to Status.FAILED_OFFER,
            "failed-answer" to Status.FAILED_ANSWER,
            "failed-ice" to Status.FAILED_ICE,
            "failed-timeout" to Status.FAILED_TIMEOUT,
        )
    }
}
