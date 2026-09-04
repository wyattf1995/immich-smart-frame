package com.wyattfleming.frameos.photos

import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

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
    data class PhotoObserved(val assetId: String, val capturedAt: Long, val jpegFile: java.io.File)

    private var configuredSession: Any? = null
    private var configuredScope: FramePhotoScope? = null
    private var extension: WebExtension? = null
    private var captureEnabled = false

    /** Configures the strict session/scope gate; this never permits another Gecko session. */
    fun configure(session: Any, photosUrl: String, profile: String): Boolean {
        val requested = FramePhotoScope.create(photosUrl, profile) ?: return false
        if (!reserve.activateScope(photosUrl, profile)) return false
        configuredSession = session
        configuredScope = requested
        captureEnabled = false
        return true
    }

    /** MainActivity must call this from Photos visibility and pause state; false is the safe default. */
    fun setCaptureEnabled(photosVisible: Boolean, photosPaused: Boolean) {
        captureEnabled = photosVisible && !photosPaused
    }

    /** Installs the APK-bundled extension and attaches its message delegate to this Photos session. */
    fun attach(runtime: GeckoRuntime, session: GeckoSession, photosUrl: String, profile: String): Boolean {
        if (!configure(session, photosUrl, profile)) return false
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept({ installed ->
            if (configuredSession === session && installed != null) {
                extension = installed
                session.webExtensionController.setMessageDelegate(installed, delegate, NATIVE_APP)
            }
        }, { _ -> })
        return true
    }

    fun detach(session: GeckoSession) {
        if (configuredSession !== session) return
        val installed = extension
        if (installed != null) session.webExtensionController.setMessageDelegate(installed, null, NATIVE_APP)
        configuredSession = null
        configuredScope = null
        captureEnabled = false
    }

    /** Testable validation path; every input received from a WebExtension is untrusted. */
    fun accept(message: Any, sender: Sender): Boolean {
        val scope = configuredScope ?: return false
        if (!captureEnabled || sender.session !== configuredSession || !sender.topLevel || !sender.contentScript || sender.url != scope.photosUrl) return false
        val objectMessage = message as? JSONObject ?: return false
        if (objectMessage.optString("type") != MESSAGE_TYPE) return false
        val assetId = objectMessage.optString("assetId")
        val image = objectMessage.optString("image")
        if (!FRAME_ASSET_ID.matches(assetId) || image.length > MAX_BASE64_CHARS || image.isBlank() || image.startsWith("data:")) return false
        val capturedAt = nowMillis()
        if (!reserve.persist(assetId, image, capturedAt)) return false
        onPhotoObserved(PhotoObserved(assetId, capturedAt, reserve.latest()?.file ?: return false))
        return true
    }

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val senderSession = sender.session
            val accepted = nativeApp == NATIVE_APP && senderSession != null && accept(
                message,
                Sender(
                    session = senderSession,
                    url = sender.url,
                    topLevel = sender.isTopLevel,
                    contentScript = sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT,
                ),
            )
            return GeckoResult.fromValue(JSONObject().put("accepted", accepted))
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
