package com.wyattfleming.frameos.photos

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import java.util.IdentityHashMap
import java.util.UUID
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
    private val uiPost: ((() -> Unit) -> Unit) = { action -> Handler(Looper.getMainLooper()).post(action) },
    private val isMainThread: () -> Boolean = { Looper.myLooper() === Looper.getMainLooper() },
    private val diagnosticLog: (String) -> Unit = { message -> Log.i(TAG, message) },
    private val onPhotoObserved: (PhotoObserved) -> Unit = {},
) {
    data class Sender(val session: Any, val url: String, val topLevel: Boolean, val contentScript: Boolean)
    /** A session-bound content-script port used only for the Photos playback controls. */
    interface PlaybackPort {
        val sender: Sender
        fun post(command: JSONObject)
        fun disconnect()
    }
    data class PhotoObserved(
        val assetId: String,
        val capturedAt: Long,
        val jpegFile: java.io.File,
        internal val generation: Long,
        internal val scopeKey: String,
        internal val pauseSnapshotNonce: String?,
    )

    @Volatile private var configuredSession: Any? = null
    @Volatile private var configuredScope: FramePhotoScope? = null
    private var extension: WebExtension? = null
    @Volatile private var captureEnabled = false
    @Volatile private var generation = 0L
    private var playbackPort: PlaybackPort? = null
    @Volatile private var desiredPlaybackPaused = false
    @Volatile private var photosVisible = false
    /** One host-issued paused snapshot is allowed only for this configured generation and scope. */
    @Volatile private var pauseSnapshotNonce: String? = null
    @Volatile private var consumedPauseSnapshotNonce: String? = null
    private val diagnosticCounts = IdentityHashMap<Any, Int>()
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
        val pauseSnapshotNonce: String?,
    )

    /** Configures the strict session/scope gate; this never permits another Gecko session. */
    @Synchronized
    fun configure(session: Any, photosUrl: String, profile: String): Boolean {
        val requested = FramePhotoScope.create(photosUrl, profile) ?: return false
        if (!reserve.activateScope(photosUrl, profile)) return false
        generation += 1
        clearPlaybackPort()
        clearPausedSnapshot()
        configuredSession = session
        configuredScope = requested
        diagnosticCounts.clear()
        photosVisible = false
        captureEnabled = false
        return true
    }

    /** MainActivity must call this from Photos visibility and pause state; false is the safe default. */
    @Synchronized
    fun setCaptureEnabled(photosVisible: Boolean, photosPaused: Boolean) {
        val becameVisible = photosVisible && !this.photosVisible
        this.photosVisible = photosVisible
        val enabled = photosVisible && !photosPaused
        if (captureEnabled && !enabled) generation += 1
        captureEnabled = enabled
        // Leaving the visible paused state revokes a callback that may still be queued on the UI thread.
        if (!photosVisible || !photosPaused) {
            clearPausedSnapshot()
        } else if (becameVisible && desiredPlaybackPaused) {
            issuePausedSnapshot()
            playbackPort?.let { dispatchPlayback(it, pauseCommand(true), queuePauseWhenOffMain = true) }
        }
    }

    /** Installs the APK-bundled extension and attaches its message delegate to this Photos session. */
    fun attach(runtime: GeckoRuntime, session: GeckoSession, photosUrl: String, profile: String): Boolean {
        if (!configure(session, photosUrl, profile)) return false
        runtime.webExtensionController.ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID).accept({ installed ->
            Handler(Looper.getMainLooper()).post {
                if (isCurrentSession(session) && installed != null) {
                    extension = installed
                    session.webExtensionController.setMessageDelegate(installed, delegate, NATIVE_APP)
                    Log.i(TAG, "Frame Photos extension ready id=${installed.id} version=${installed.metaData.version}")
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
        diagnosticCounts.clear()
        photosVisible = false
        captureEnabled = false
        generation += 1
        clearPausedSnapshot()
        clearPlaybackPort()
    }

    /** Final lifecycle cleanup; [detach] remains reusable for Photos-session rebuilds. */
    @Synchronized
    fun close() {
        generation += 1
        photosVisible = false
        captureEnabled = false
        clearPausedSnapshot()
        clearPlaybackPort()
        desiredPlaybackPaused = false
        decodeWorker.shutdownNow()
    }

    /** Records desired state even while the page reconnects; true means a current port accepted the dispatch. */
    @Synchronized
    fun setPlaybackPaused(paused: Boolean): Boolean {
        if (!paused) {
            desiredPlaybackPaused = false
            clearPausedSnapshot()
        } else {
            val changed = !desiredPlaybackPaused
            desiredPlaybackPaused = true
            if (changed) issuePausedSnapshot()
        }
        return playbackPort?.let { dispatchPlayback(it, pauseCommand(paused), queuePauseWhenOffMain = true) } ?: false
    }

    private fun issuePausedSnapshot() {
        if (!photosVisible || !desiredPlaybackPaused) return
        pauseSnapshotNonce = UUID.randomUUID().toString()
        consumedPauseSnapshotNonce = null
    }

    private fun clearPausedSnapshot() {
        pauseSnapshotNonce = null
        consumedPauseSnapshotNonce = null
    }

    private fun pauseCommand(paused: Boolean): JSONObject = JSONObject().put("type", "pause").put("paused", paused).also { command ->
        if (paused) pauseSnapshotNonce?.let { command.put(PAUSE_SNAPSHOT_NONCE, it) }
    }

    /** Sends one trusted next/previous request; it never falls back to synthetic surface input. */
    @Synchronized
    fun movePhoto(forward: Boolean): Boolean {
        val port = playbackPort ?: return false
        val priorSnapshot = pauseSnapshotNonce
        val priorConsumed = consumedPauseSnapshotNonce
        val step = JSONObject().put("type", "step").put("forward", forward)
        if (photosVisible && desiredPlaybackPaused) {
            pauseSnapshotNonce = UUID.randomUUID().toString()
            consumedPauseSnapshotNonce = null
            step.put(PAUSE_SNAPSHOT_NONCE, pauseSnapshotNonce)
        }
        val dispatched = dispatchPlayback(port, step, queuePauseWhenOffMain = false)
        if (!dispatched) {
            pauseSnapshotNonce = priorSnapshot
            consumedPauseSnapshotNonce = priorConsumed
        }
        return dispatched
    }

    /** Testable strict port gate; the real delegate adapts Gecko's port into this narrow interface. */
    @Synchronized
    internal fun connectPlaybackPort(port: PlaybackPort): Boolean {
        if (!isTrustedPlaybackPort(port)) {
            port.disconnect()
            return false
        }
        clearPlaybackPort()
        playbackPort = port
        dispatchPlayback(port, pauseCommand(desiredPlaybackPaused), queuePauseWhenOffMain = true)
        return true
    }

    @Synchronized
    private fun clearPlaybackPort() {
        playbackPort?.disconnect()
        playbackPort = null
    }

    private fun isTrustedPlaybackPort(port: PlaybackPort): Boolean {
        val scope = configuredScope ?: return false
        val sender = port.sender
        return sender.session === configuredSession && sender.topLevel && sender.contentScript && sender.url == scope.photosUrl
    }

    private fun dispatchPlayback(port: PlaybackPort, command: JSONObject, queuePauseWhenOffMain: Boolean): Boolean {
        if (playbackPort !== port || !isTrustedPlaybackPort(port)) return false
        if (isMainThread()) return postPlayback(port, command)
        if (queuePauseWhenOffMain) {
            uiPost {
                synchronized(this) {
                    postPlayback(port, command)
                }
            }
        }
        return false
    }

    private fun postPlayback(port: PlaybackPort, command: JSONObject): Boolean {
        if (playbackPort !== port || !isTrustedPlaybackPort(port)) return false
        return runCatching {
            port.post(command)
            true
        }.getOrElse {
            if (playbackPort === port) playbackPort = null
            false
        }
    }

    /** Testable validation path; every input received from a WebExtension is untrusted. */
    fun accept(message: Any, sender: Sender): Boolean {
        val capture = captureFrom(message, sender) ?: return false
        if (!reserve.persist(capture.assetId, capture.image, capture.capturedAt, capture.scope.key) { canCommit(capture) }) return false
        return reportIfCurrent(capture)
    }

    /** Accepts only scalar diagnostics from the same configured Photos content script. */
    @Synchronized
    internal fun acceptDiagnostic(message: Any, sender: Sender): Boolean {
        val scope = configuredScope ?: return false
        if (!isTrustedSender(scope, sender)) return false
        val objectMessage = message as? JSONObject ?: return false
        if (objectMessage.optString("type") != DIAGNOSTIC_TYPE) return false
        val stage = objectMessage.optString("stage")
        if (stage !in DIAGNOSTIC_STAGES) return false
        val booleanFields = listOf(
            "detailReadable",
            "targetIsKiosk",
            "xhrPresent",
            "primaryTarget",
            "desiredPaused",
            "noncePresent",
        )
        if (booleanFields.any { objectMessage.opt(it) !is Boolean }) return false
        val settledEpoch = objectMessage.opt("settledEpoch") as? Number ?: return false
        val trackedCount = objectMessage.opt("trackedCount") as? Number ?: return false
        if (!boundedWholeNumber(settledEpoch, MAX_DIAGNOSTIC_EPOCH) || !boundedWholeNumber(trackedCount, MAX_DIAGNOSTIC_TRACKED_COUNT)) return false
        val count = diagnosticCounts[sender.session] ?: 0
        if (count >= MAX_DIAGNOSTICS_PER_SESSION) return false
        diagnosticCounts[sender.session] = count + 1
        diagnosticLog(
            "Frame Photos diagnostic stage=$stage detailReadable=${objectMessage.getBoolean("detailReadable")} " +
                "targetIsKiosk=${objectMessage.getBoolean("targetIsKiosk")} xhrPresent=${objectMessage.getBoolean("xhrPresent")} " +
                "primaryTarget=${objectMessage.getBoolean("primaryTarget")} desiredPaused=${objectMessage.getBoolean("desiredPaused")} " +
                "noncePresent=${objectMessage.getBoolean("noncePresent")} settledEpoch=${settledEpoch.toLong()} trackedCount=${trackedCount.toLong()}",
        )
        return true
    }

    private fun captureFrom(message: Any, sender: Sender): Capture? = synchronized(this) {
        val scope = configuredScope ?: return@synchronized null
        if (!isTrustedSender(scope, sender)) return@synchronized null
        val objectMessage = message as? JSONObject ?: return@synchronized null
        if (objectMessage.optString("type") != MESSAGE_TYPE) return@synchronized null
        val requestedNonce = objectMessage.optString(PAUSE_SNAPSHOT_NONCE, "")
        val snapshotNonce = requestedNonce.takeIf { it.isNotBlank() }
        if (snapshotNonce != null && (snapshotNonce != pauseSnapshotNonce || !photosVisible || !desiredPlaybackPaused)) return@synchronized null
        if (!captureEnabled && snapshotNonce == null) return@synchronized null
        val assetId = objectMessage.optString("assetId")
        val image = objectMessage.optString("image")
        if (!FRAME_ASSET_ID.matches(assetId) || image.length > MAX_BASE64_CHARS || image.isBlank() || image.startsWith("data:")) return@synchronized null
        Capture(generation, sender.session, scope, assetId, image, nowMillis(), snapshotNonce)
    }

    private fun reportIfCurrent(capture: Capture): Boolean = synchronized(this) {
        if (!isCurrent(capture)) return@synchronized false
        val file = reserve.latest()?.file ?: return@synchronized false
        capture.pauseSnapshotNonce?.let {
            if (it != pauseSnapshotNonce) return@synchronized false
            pauseSnapshotNonce = null
            consumedPauseSnapshotNonce = it
        }
        onPhotoObserved(PhotoObserved(capture.assetId, capture.capturedAt, file, capture.generation, capture.scope.key, capture.pauseSnapshotNonce))
        true
    }

    /** Recheck when consuming a callback posted asynchronously by the host activity. */
    @Synchronized
    fun isCurrentObservation(photo: PhotoObserved): Boolean =
        generation == photo.generation && configuredScope?.key == photo.scopeKey &&
            if (photo.pauseSnapshotNonce != null) {
                photosVisible && desiredPlaybackPaused && photo.pauseSnapshotNonce == consumedPauseSnapshotNonce
            } else {
                captureEnabled
            }

    private fun isCurrent(capture: Capture): Boolean =
        generation == capture.generation && configuredSession === capture.session && configuredScope?.key == capture.scope.key &&
            if (capture.pauseSnapshotNonce != null) {
                photosVisible && desiredPlaybackPaused && capture.pauseSnapshotNonce == pauseSnapshotNonce
            } else {
                captureEnabled
            }

    /** This intentionally avoids the bridge monitor: Reserve invokes it under its own monitor. */
    private fun canCommit(capture: Capture): Boolean = isCurrent(capture)

    private fun isTrustedSender(scope: FramePhotoScope, sender: Sender): Boolean =
        sender.session === configuredSession && sender.topLevel && sender.contentScript && sender.url == scope.photosUrl

    private fun boundedWholeNumber(value: Number, maximum: Long): Boolean =
        value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble() && value.toLong() in 0L..maximum

    @Synchronized
    private fun isCurrentSession(session: Any): Boolean = configuredSession === session

    private inner class GeckoPlaybackPort(private val port: WebExtension.Port) : PlaybackPort {
        override val sender = port.sender.let {
            Sender(
                session = it.session ?: Any(),
                url = it.url,
                topLevel = it.isTopLevel,
                contentScript = it.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT,
            )
        }

        override fun post(command: JSONObject) = port.postMessage(command)
        override fun disconnect() = port.disconnect()
        fun bind() {
            port.setDelegate(object : WebExtension.PortDelegate {
                override fun onDisconnect(port: WebExtension.Port) {
                    synchronized(this@FramePhotoBridge) {
                        if (playbackPort === this@GeckoPlaybackPort) playbackPort = null
                    }
                }
            })
        }
    }

    private val delegate = object : WebExtension.MessageDelegate {
        override fun onConnect(port: WebExtension.Port) {
            val playback = GeckoPlaybackPort(port)
            playback.bind()
            connectPlaybackPort(playback)
        }

        override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
            val senderSession = sender.session
            if (nativeApp != NATIVE_APP || senderSession == null) return GeckoResult.fromValue(JSONObject().put("accepted", false))
            val trustedSender = Sender(
                session = senderSession,
                url = sender.url,
                topLevel = sender.isTopLevel,
                contentScript = sender.environmentType == WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT,
            )
            if ((message as? JSONObject)?.optString("type") == DIAGNOSTIC_TYPE) {
                return GeckoResult.fromValue(JSONObject().put("accepted", acceptDiagnostic(message, trustedSender)))
            }
            val capture = captureFrom(
                message,
                trustedSender,
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
        private const val TAG = "FramePhotoBridge"
        const val MAX_BASE64_CHARS = 4 * 1024 * 1024
        const val EXTENSION_LOCATION = "resource://android/assets/frame-photo-bridge/"
        const val EXTENSION_ID = "frame-photo-bridge@wyattfleming.com"
        const val NATIVE_APP = "frame_photo_bridge"
        private const val MESSAGE_TYPE = "loaded-photo"
        private const val PAUSE_SNAPSHOT_NONCE = "snapshotNonce"
        private const val DIAGNOSTIC_TYPE = "bridge-diagnostic"
        private const val MAX_DIAGNOSTICS_PER_SESSION = 32
        private const val MAX_DIAGNOSTIC_EPOCH = 4096L
        private const val MAX_DIAGNOSTIC_TRACKED_COUNT = 4L
        private val DIAGNOSTIC_STAGES = setOf("pause", "step", "htmx_before", "htmx_primary_settle", "htmx_error")
    }
}
