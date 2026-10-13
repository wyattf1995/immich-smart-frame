package com.wyattfleming.touchframe.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.wyattfleming.frameos.photos.FramePhotoBridge
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.util.concurrent.atomic.AtomicLong

/**
 * Device-private offline fallback for the actual configured Photos session only.
 * Art never attaches the bridge and never reads this reserve. Disk work and JPEG
 * decode run on the store worker; UI delivery is fenced by [fallbackGeneration].
 */
internal class TouchOfflinePhotos(
    context: Context,
    private val store: TouchOfflinePhotoStore,
    private val onPhotoObservedCallback: (FramePhotoBridge.PhotoObserved) -> Unit = {},
) : AutoCloseable {
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val foreground = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "Recent photo while Photos reconnects"
    }
    val view = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(Color.BLACK)
        addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        addView(foreground, FrameLayout.LayoutParams(-1, -1))
    }

    private val main = Handler(Looper.getMainLooper())
    private val bridge = FramePhotoBridge(store.reserve, onPhotoObserved = ::onObserved)
    private val lifecycle = TouchOfflinePhotoLifecycle()
    private val captureGate = TouchOfflineCaptureGate()
    private val attachmentIdentity = TouchOfflineAttachmentIdentity()
    @Volatile private var closed = false
    @Volatile private var fallbackGeneration = 0L
    private val attachmentGate = TouchOfflineAttachmentGate()
    @Volatile private var session: GeckoSession? = null
    private var bitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var displayedAssetId: String? = null

    fun attach(runtime: GeckoRuntime, target: GeckoSession, photosUrl: String, profile: String, active: Boolean) {
        if (!closed && attachmentIdentity.matches(target, photosUrl, profile)) {
            setActive(active)
            return
        }
        detachCurrent()
        session = target
        attachmentIdentity.replace(target, photosUrl, profile)
        lifecycle.attach(target)
        captureGate.setActive(active && !closed)
        lifecycle.setActive(captureGate.isActive())
        val expectedAttachment = attachmentGate.begin()
        store.prepareBridge(bridge, target, photosUrl, profile) {
            !closed && session === target && attachmentGate.isCurrent(expectedAttachment)
        }.let { preparation ->
            store.execute {
                val prepared = runCatching { preparation.get() }.getOrDefault(false)
                if (!prepared) {
                    main.post {
                        if (!closed && session === target && attachmentGate.isCurrent(expectedAttachment)) detach(target)
                    }
                    return@execute
                }
                if (closed || session !== target || !attachmentGate.isCurrent(expectedAttachment)) {
                    bridge.detach(target)
                    return@execute
                }
                main.post {
                    if (closed || session !== target || !attachmentGate.isCurrent(expectedAttachment)) {
                        bridge.detach(target)
                        return@post
                    }
                    // Gecko extension creation stays on the UI thread after worker scope activation.
                    bridge.attachPrepared(runtime, target) { ready ->
                        if (closed || session !== target || !attachmentGate.isCurrent(expectedAttachment) || !ready) return@attachPrepared
                        queueCaptureGate(target, expectedAttachment)
                    }
                }
            }
        }
    }

    fun setActive(active: Boolean) {
        captureGate.setActive(active && !closed && session != null)
        lifecycle.setActive(captureGate.isActive())
        session?.let { queueCaptureGate(it, attachmentGate.current()) }
        if (!captureGate.isActive()) hideFallback()
    }

    fun detach(target: GeckoSession) {
        if (session !== target) return
        captureGate.setActive(false)
        lifecycle.detach(target)
        fallbackGeneration += 1L
        attachmentGate.invalidate()
        bridge.detach(target)
        session = null
        attachmentIdentity.clear()
        hideFallback()
    }

    fun showFallback() {
        val attached = session ?: return
        if (!captureGate.isActive() || closed) return
        val lifecycleGeneration = lifecycle.currentGeneration()
        val generation = ++fallbackGeneration
        store.execute {
            val entry = store.reserve.latest() ?: return@execute
            val decoded = runCatching { BitmapFactory.decodeFile(entry.file.path) }.getOrNull() ?: return@execute
            val blurred = blurBackdrop(decoded)
            main.post {
                if (closed || !captureGate.isActive() || generation != fallbackGeneration || !lifecycle.canUseFallback(attached, lifecycleGeneration)) {
                    decoded.recycle()
                    blurred?.recycle()
                    return@post
                }
                val previous = bitmap
                bitmap = decoded
                val previousBlurred = blurredBitmap
                blurredBitmap = blurred
                displayedAssetId = entry.assetId
                backdrop.setImageBitmap(blurred)
                foreground.setImageBitmap(decoded)
                view.visibility = View.VISIBLE
                previous?.recycle()
                previousBlurred?.recycle()
            }
        }
    }

    fun hideFallback() {
        fallbackGeneration += 1L
        if (view.visibility != View.VISIBLE && bitmap == null) return
        view.visibility = View.GONE
        foreground.setImageDrawable(null)
        backdrop.setImageDrawable(null)
        bitmap?.recycle()
        blurredBitmap?.recycle()
        bitmap = null
        blurredBitmap = null
        displayedAssetId = null
    }

    fun setPaused(paused: Boolean): Boolean {
        captureGate.setPaused(paused)
        session?.let { queueCaptureGate(it, attachmentGate.current()) }
        return bridge.setPlaybackPaused(paused)
    }
    fun move(forward: Boolean): Boolean = bridge.movePhoto(forward)
    fun applyHiddenAssets(assetIds: Set<String>, canCommit: () -> Boolean = { true }) =
        store.applyHiddenAssets(assetIds, canCommit)

    fun hideFallbackIfHidden(assetIds: Set<String>) {
        main.post {
            if (TouchOfflineHiddenAssetPolicy.hides(displayedAssetId, assetIds)) hideFallback()
        }
    }

    fun isOfflineVisible(): Boolean = view.visibility == View.VISIBLE

    override fun close() {
        if (closed) return
        closed = true
        captureGate.setActive(false)
        fallbackGeneration += 1L
        attachmentGate.invalidate()
        session?.let { lifecycle.detach(it); bridge.detach(it) }
        session = null
        attachmentIdentity.clear()
        bridge.close()
        hideFallback()
    }

    private fun detachCurrent() {
        session?.let { lifecycle.detach(it); bridge.detach(it) }
        session = null
        attachmentIdentity.clear()
        fallbackGeneration += 1L
        attachmentGate.invalidate()
        hideFallback()
    }


    private fun queueCaptureGate(target: GeckoSession, expectedAttachment: Long) {
        store.execute {
            if (!closed && session === target && attachmentGate.isCurrent(expectedAttachment)) {
                bridge.setCaptureEnabled(captureGate.isActive(), captureGate.isPaused())
            }
        }
    }

    /** Worker-only: blur a bounded mutable ARGB copy before center-cropping the backdrop. */
    private fun blurBackdrop(source: Bitmap): Bitmap? = runCatching {
        val plan = TouchOfflineBlurPlan.forForeground(source.width, source.height)
        val scaled = if (plan.backdropWidth == source.width && plan.backdropHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, plan.backdropWidth, plan.backdropHeight, true)
        }
        // Never mutate source: it remains the foreground's uncropped FIT_CENTER bitmap.
        val small = try {
            scaled.copy(Bitmap.Config.ARGB_8888, true) ?: return@runCatching null
        } finally {
            if (scaled !== source) scaled.recycle()
        }
        val width = small.width
        val height = small.height
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val temp = pixels.copyOf()
        fun pass(input: IntArray, output: IntArray, horizontal: Boolean) {
            for (y in 0 until height) for (x in 0 until width) {
                var a = 0; var r = 0; var g = 0; var b = 0; var count = 0
                for (offset in -3..3) {
                    val xx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                    val yy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                    val value = input[yy * width + xx]
                    a += value ushr 24; r += (value shr 16) and 0xff
                    g += (value shr 8) and 0xff; b += value and 0xff; count++
                }
                output[y * width + x] =
                    ((a / count) shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        repeat(2) { pass(temp, pixels, true); pass(pixels, temp, false) }
        small.setPixels(temp, 0, width, 0, 0, width, height)
        small
    }.getOrNull()

    private fun onObserved(photo: FramePhotoBridge.PhotoObserved) {
        main.post {
            if (!closed && captureGate.isActive() && lifecycle.canUseFallback(session, lifecycle.currentGeneration()) && bridge.isCurrentObservation(photo)) hideFallback()
            onPhotoObservedCallback(photo)
        }
    }
}

/** Normalizes server-hidden asset IDs before retaining a cached fallback on screen. */
internal object TouchOfflineHiddenAssetPolicy {
    fun hides(displayedAssetId: String?, hiddenAssetIds: Set<String>): Boolean =
        displayedAssetId != null && hiddenAssetIds.any { it.equals(displayedAssetId, ignoreCase = true) }
}

/** Thread-safe active/paused state used at every trusted capture gate. */
internal class TouchOfflineCaptureGate {
    @Volatile private var active = false
    @Volatile private var paused = false

    fun setActive(value: Boolean) { active = value }
    fun setPaused(value: Boolean) { paused = value }
    fun isActive(): Boolean = active
    fun isPaused(): Boolean = paused
    fun captureEnabled(): Boolean = active && !paused
}

/** Exact session/URL/profile identity that permits a no-work return to Photos. */
internal class TouchOfflineAttachmentIdentity {
    private var session: Any? = null
    private var photosUrl: String? = null
    private var profile: String? = null

    fun matches(target: Any, url: String, requestedProfile: String): Boolean =
        session === target && photosUrl == url && profile == requestedProfile

    fun replace(target: Any, url: String, requestedProfile: String) {
        session = target
        photosUrl = url
        profile = requestedProfile
    }

    fun clear() {
        session = null
        photosUrl = null
        profile = null
    }
}

/** Atomic session-generation fence shared by the UI and reserve worker. */
internal class TouchOfflineAttachmentGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()
    fun invalidate(): Long = generation.incrementAndGet()
    fun current(): Long = generation.get()
    fun isCurrent(expected: Long): Boolean = generation.get() == expected
}

/** Pure lifecycle fence: only the current, visible Photos attachment may use fallback data. */
internal class TouchOfflinePhotoLifecycle {
    private var attachment: Any? = null
    private var active = false
    private var generation = 0L

    fun attach(session: Any): Long {
        attachment = session
        active = false
        return ++generation
    }

    fun setActive(value: Boolean) { active = value && attachment != null }

    fun detach(session: Any): Boolean {
        if (attachment !== session) return false
        attachment = null
        active = false
        generation += 1L
        return true
    }

    fun canUseFallback(session: Any?, expectedGeneration: Long): Boolean =
        active && attachment === session && generation == expectedGeneration

    fun currentGeneration(): Long = generation
}

/** A bounded backdrop copy never changes the foreground bitmap's intended dimensions. */
internal object TouchOfflineBlurPlan {
    const val MAX_BACKDROP_EDGE = 128

    data class Plan(
        val foregroundWidth: Int,
        val foregroundHeight: Int,
        val backdropWidth: Int,
        val backdropHeight: Int,
    )

    fun forForeground(width: Int, height: Int): Plan {
        require(width > 0 && height > 0) { "bitmap dimensions must be positive" }
        val largest = maxOf(width, height)
        val scale = minOf(1f, MAX_BACKDROP_EDGE.toFloat() / largest)
        return Plan(
            foregroundWidth = width,
            foregroundHeight = height,
            backdropWidth = (width * scale).toInt().coerceAtLeast(1),
            backdropHeight = (height * scale).toInt().coerceAtLeast(1),
        )
    }
}
