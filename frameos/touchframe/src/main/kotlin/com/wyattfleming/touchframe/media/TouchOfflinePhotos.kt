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
import com.wyattfleming.frameos.photos.FrameOfflineReserve
import com.wyattfleming.frameos.photos.FramePhotoBridge
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Device-private offline fallback for the actual configured Photos session only.
 * Art never attaches the bridge and never reads this reserve. Disk work and JPEG
 * decode run on [worker]; UI delivery is fenced by [fallbackGeneration].
 */
internal class TouchOfflinePhotos(
    context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "touchframe-offline-photos").apply { isDaemon = true }
    },
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
    private val reserve = FrameOfflineReserve(File(context.filesDir, "touchframe-offline-photos"))
    private val bridge = FramePhotoBridge(reserve, onPhotoObserved = ::onObserved)
    private val lifecycle = TouchOfflinePhotoLifecycle()
    @Volatile private var photosActive = false
    @Volatile private var closed = false
    @Volatile private var fallbackGeneration = 0L
    private val attachmentGate = TouchOfflineAttachmentGate()
    @Volatile private var session: GeckoSession? = null
    private var bitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null

    fun attach(runtime: GeckoRuntime, target: GeckoSession, photosUrl: String, profile: String, active: Boolean) {
        detachCurrent()
        session = target
        lifecycle.attach(target)
        photosActive = active
        lifecycle.setActive(active)
        val expectedAttachment = attachmentGate.begin()
        worker.execute {
            if (closed || session !== target || !attachmentGate.isCurrent(expectedAttachment) ||
                !bridge.configure(target, photosUrl, profile)
            ) return@execute
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
                    worker.execute {
                        if (!closed && session === target && attachmentGate.isCurrent(expectedAttachment)) {
                            bridge.setCaptureEnabled(photosActive, photosPaused = false)
                        }
                    }
                }
            }
        }
    }

    fun setActive(active: Boolean) {
        photosActive = active && !closed && session != null
        lifecycle.setActive(photosActive)
        val attached = session
        val expectedAttachment = attachmentGate.current()
        if (attached != null) worker.execute {
            if (!closed && session === attached && attachmentGate.isCurrent(expectedAttachment)) {
                bridge.setCaptureEnabled(photosActive, photosPaused = false)
            }
        }
        if (!photosActive) hideFallback()
    }

    fun detach(target: GeckoSession) {
        if (session !== target) return
        photosActive = false
        lifecycle.detach(target)
        fallbackGeneration += 1L
        attachmentGate.invalidate()
        bridge.detach(target)
        session = null
        hideFallback()
    }

    fun showFallback() {
        val attached = session ?: return
        if (!photosActive || closed) return
        val lifecycleGeneration = lifecycle.currentGeneration()
        val generation = ++fallbackGeneration
        worker.execute {
            val entry = reserve.latest() ?: return@execute
            val decoded = runCatching { BitmapFactory.decodeFile(entry.file.path) }.getOrNull() ?: return@execute
            val blurred = blurBackdrop(decoded)
            main.post {
                if (closed || !photosActive || generation != fallbackGeneration || !lifecycle.canUseFallback(attached, lifecycleGeneration)) {
                    decoded.recycle()
                    blurred?.recycle()
                    return@post
                }
                val previous = bitmap
                bitmap = decoded
                val previousBlurred = blurredBitmap
                blurredBitmap = blurred
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
    }

    fun setPaused(paused: Boolean): Boolean = bridge.setPlaybackPaused(paused)
    fun move(forward: Boolean): Boolean = bridge.movePhoto(forward)
    fun applyHiddenAssets(assetIds: Set<String>, canCommit: () -> Boolean = { true }) =
        worker.submit<Boolean> { reserve.applyHiddenAssets(assetIds, canCommit) }

    override fun close() {
        if (closed) return
        closed = true
        photosActive = false
        fallbackGeneration += 1L
        attachmentGate.invalidate()
        session?.let { lifecycle.detach(it); bridge.detach(it) }
        session = null
        bridge.close()
        worker.shutdownNow()
        hideFallback()
    }

    private fun detachCurrent() {
        session?.let { lifecycle.detach(it); bridge.detach(it) }
        session = null
        fallbackGeneration += 1L
        attachmentGate.invalidate()
        hideFallback()
    }


    /** Worker-only: blur a bounded copy before ImageView center-crops the backdrop. */
    private fun blurBackdrop(source: Bitmap): Bitmap? = runCatching {
        val plan = TouchOfflineBlurPlan.forForeground(source.width, source.height)
        val small = if (plan.backdropWidth == source.width && plan.backdropHeight == source.height) {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createScaledBitmap(source, plan.backdropWidth, plan.backdropHeight, true)
        }
        // Never mutate source: it remains the foreground's uncropped FIT_CENTER bitmap.
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
            if (!closed && photosActive && lifecycle.canUseFallback(session, lifecycle.currentGeneration()) && bridge.isCurrentObservation(photo)) hideFallback()
        }
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
