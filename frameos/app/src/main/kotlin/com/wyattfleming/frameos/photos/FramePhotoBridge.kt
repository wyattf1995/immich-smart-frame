package com.wyattfleming.frameos.photos

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Native endpoint for the built-in Photos content script. Call [attach] only for the
 * configured Photos GeckoSession, and [detach] before that session is closed. The
 * callback is the integration seam for FrameOS currentAssetId/lastPhotoAt health and
 * the JPEG file is available through [FrameOfflineReserve.latest] for offline UI.
 */
class FramePhotoBridge(
    private val reserve: FrameOfflineReserve,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onPhotoObserved: (PhotoObserved) -> Unit = {},
) {
    data class Sender(val session: Any, val url: String, val topLevel: Boolean, val contentScript: Boolean)
    data class PhotoObserved(
        val assetId: String,
        val capturedAt: Long,
        val jpegFile: java.io.File,
        internal val generation: Long,
        internal val scopeKey: String,
    )

    @Volatile private var configuredSession: Any? = null
    @Volatile private var configuredScope: FramePhotoScope? = null
    private var extension: WebExtension? = null
    @Volatile private var captureEnabled = false
    @Volatile private var generation = 0L
    private val decodeWorker = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        ThreadFactory { runnable -> Thread(runnable, "frame-photo-decode").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private data class Capture(
        val generation: Long,
        val session: Any,
        val scope: FramePhotoScope,
        val assetId: String,
        val image: String,
        val capturedAt: Long,
    )

    /** Configures the strict session/scope gate; this never permits another Gecko session. */
    @Synchronized
    fun configure(session: Any, photosUrl: String, profile: String): Boolean {
        val requested = FramePhotoScope.create(photosUrl, profile) ?: return false
        if (!reserve.activateScope(photosUrl, profile)) return false
        generation += 1
        configuredSession = session
        configuredScope = requested
        captureEnabled = false
        return true
    }

    /** MainActivity must call this from Photos visibility and pause state; false is the safe default. */
    @Synchronized
    fun setCaptureEnabled(photosVisible: Boolean, photosPaused: Boolean) {
        val enabled = photosVisible && !photosPaused
        if (captureEnabled && !enabled) generation += 1
        captureEnabled = enabled
    }

    /** Installs the APK-bundled extension and attaches its message delegate to this Photos session. */
    fun attach(runtime: GeckoRuntime, session: GeckoSession, photosUrl: String, profile: String): Boolean {
        if (!configure(session, photosUrl, profile)) return false
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept({ installed ->
            Handler(Looper.getMainLooper()).post {
                if (isCurrentSession(session) && installed != null) {
                    extension = installed
                    session.webExtensionController.setMessageDelegate(installed, delegate, NATIVE_APP)
                }
            }
        }, { _ -> })
        return true
    }

    @Synchronized
    fun detach(session: GeckoSession) {
        if (configuredSession !== session) return
        val installed = extension
        if (installed != null) session.webExtensionController.setMessageDelegate(installed, null, NATIVE_APP)
        configuredSession = null
        configuredScope = null
        captureEnabled = false
        generation += 1
    }

    /** Final lifecycle cleanup; [detach] remains reusable for Photos-session rebuilds. */
    @Synchronized
    fun close() {
        generation += 1
        captureEnabled = false
        decodeWorker.shutdownNow()
    }

    /** Testable validation path; every input received from a WebExtension is untrusted. */
    fun accept(message: Any, sender: Sender): Boolean {
        val capture = captureFrom(message, sender) ?: return false
        if (!reserve.persist(capture.assetId, capture.image, capture.capturedAt, capture.scope.key) { canCommit(capture) }) return false
        return reportIfCurrent(capture)
    }

    private fun captureFrom(message: Any, sender: Sender): Capture? = synchronized(this) {
        val scope = configuredScope ?: return@synchronized null
        if (!captureEnabled || sender.session !== configuredSession || !sender.topLevel || !sender.contentScript || sender.url != scope.photosUrl) return@synchronized null
        val objectMessage = message as? JSONObject ?: return@synchronized null
        if (objectMessage.optString("type") != MESSAGE_TYPE) return@synchronized null
        val assetId = objectMessage.optString("assetId")
        val image = objectMessage.optString("image")
        if (!FRAME_ASSET_ID.matches(assetId) || image.length > MAX_BASE64_CHARS || image.isBlank() || image.startsWith("data:")) return@synchronized null
        Capture(generation, sender.session, scope, assetId, image, nowMillis())
    }

    private fun reportIfCurrent(capture: Capture): Boolean = synchronized(this) {
        if (!isCurrent(capture)) return@synchronized false
        val file = reserve.latest()?.file ?: return@synchronized false
        onPhotoObserved(PhotoObserved(capture.assetId, capture.capturedAt, file, capture.generation, capture.scope.key))
        true
    }

    /** Recheck when consuming a callback posted asynchronously by the host activity. */
    @Synchronized
    fun isCurrentObservation(photo: PhotoObserved): Boolean =
        captureEnabled && generation == photo.generation && configuredScope?.key == photo.scopeKey

    private fun isCurrent(capture: Capture): Boolean =
        generation == capture.generation && captureEnabled && configuredSession === capture.session && configuredScope?.key == capture.scope.key

    /** This intentionally avoids the bridge monitor: Reserve invokes it under its own monitor. */
    private fun canCommit(capture: Capture): Boolean = isCurrent(capture)

    @Synchronized
    private fun isCurrentSession(session: Any): Boolean = configuredSession === session

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val senderSession = sender.session
            if (nativeApp != NATIVE_APP || senderSession == null) return GeckoResult.fromValue(JSONObject().put("accepted", false))
            val capture = captureFrom(
                message,
                Sender(
                    session = senderSession,
                    url = sender.url,
                    topLevel = sender.isTopLevel,
                    contentScript = sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT,
                ),
            )
            if (capture == null) return GeckoResult.fromValue(JSONObject().put("accepted", false))
            val result = GeckoResult<Any>()
            try {
                decodeWorker.execute {
                    val accepted = reserve.persist(capture.assetId, capture.image, capture.capturedAt, capture.scope.key) { canCommit(capture) } && reportIfCurrent(capture)
                    result.complete(JSONObject().put("accepted", accepted))
                }
            } catch (_: RejectedExecutionException) {
                result.complete(JSONObject().put("accepted", false))
            }
            return result
        }
    }

    companion object {
        const val MAX_BASE64_CHARS = 4 * 1024 * 1024
        const val EXTENSION_LOCATION = "resource://android/assets/frame-photo-bridge/"
        const val EXTENSION_ID = "frame-photo-bridge@wyattfleming.com"
        const val NATIVE_APP = "frame_photo_bridge"
        private const val MESSAGE_TYPE = "loaded-photo"
    }
}
