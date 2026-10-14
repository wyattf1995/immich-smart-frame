package com.wyattfleming.touchframe.media

import java.net.URI

/**
 * Immutable network and sender policy for the one-camera live surface.
 *
 * The WebRTC page is packaged with the APK. It receives an in-memory token only
 * through a one-shot native capability and can only open the configured Home
 * Assistant websocket endpoint.
 */
internal class TouchLiveCameraPolicy(origin: String, cameraIds: List<String>) {
    private val configuredOrigin = URI(origin).also { uri ->
        require(uri.scheme == "https" || uri.scheme == "http")
        require(!uri.host.isNullOrBlank() && uri.userInfo == null)
        require(uri.query == null && uri.fragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
    }
    private val cameras = cameraIds.toSet().also { configured ->
        require(configured.isNotEmpty() && configured.all { TouchCameraPolicy.validCamera(it) })
    }

    val websocketUrl: URI = URI(
        if (configuredOrigin.scheme == "https") "wss" else "ws",
        null,
        configuredOrigin.host,
        configuredOrigin.port,
        "/api/websocket",
        null,
        null,
    )

    fun allowsCamera(entityId: String): Boolean = entityId in cameras

    /** Reject query and fragment additions: only the exact packaged document is trusted. */
    fun isTrustedPageUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "moz-extension" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path == "/live.html"
    }.getOrDefault(false)
}

/** One native-issued capability may start exactly one currently visible live session. */
internal class TouchLiveCameraCapabilityGate {
    data class Capability(val generation: Long, val entityId: String, val nonce: String)

    private var generation = 0L
    private var active: Capability? = null
    private var consumed = false

    @Synchronized fun begin(entityId: String, nonce: String): Capability {
        require(nonce.length >= 16)
        val next = Capability(++generation, entityId, nonce)
        active = next
        consumed = false
        return next
    }

    @Synchronized fun accepts(generation: Long, entityId: String, nonce: String, visible: Boolean): Boolean =
        visible && !consumed && active?.let { it.generation == generation && it.entityId == entityId && it.nonce == nonce } == true

    @Synchronized fun consume(generation: Long, entityId: String, nonce: String, visible: Boolean): Boolean {
        if (!accepts(generation, entityId, nonce, visible)) return false
        consumed = true
        return true
    }

    @Synchronized fun current(): Capability? = active

    @Synchronized fun revoke() {
        generation += 1
        active = null
        consumed = false
    }
}
