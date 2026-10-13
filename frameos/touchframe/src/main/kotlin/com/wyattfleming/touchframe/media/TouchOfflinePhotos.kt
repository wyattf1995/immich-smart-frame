package com.wyattfleming.touchframe.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.wyattfleming.frameos.photos.FrameOfflineReserve
import com.wyattfleming.frameos.photos.FramePhotoBridge
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    val view = ImageView(context).apply {
        visibility = View.GONE
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        contentDescription = "Recent photo while Photos reconnects"
    }

    private val main = Handler(Looper.getMainLooper())
    private val reserve = FrameOfflineReserve(File(context.filesDir, "touchframe-offline-photos"))
    private val bridge = FramePhotoBridge(reserve, onPhotoObserved = ::onObserved)
    private val lifecycle = TouchOfflinePhotoLifecycle()
    @Volatile private var photosActive = false
    @Volatile private var closed = false
    @Volatile private var fallbackGeneration = 0L
    @Volatile private var session: GeckoSession? = null
    private var bitmap: Bitmap? = null

    fun attach(runtime: GeckoRuntime, target: GeckoSession, photosUrl: String, profile: String, active: Boolean) {
        detachCurrent()
        session = target
        lifecycle.attach(target)
        photosActive = active
        lifecycle.setActive(active)
        worker.execute {
            if (closed || session !== target) return@execute
            bridge.attach(runtime, target, photosUrl, profile) { ready ->
                if (closed || session !== target || !ready) return@attach
                bridge.setCaptureEnabled(photosActive, photosPaused = false)
            }
        }
    }

    fun setActive(active: Boolean) {
        photosActive = active && !closed && session != null
        lifecycle.setActive(photosActive)
        bridge.setCaptureEnabled(photosActive, photosPaused = false)
        if (!photosActive) hideFallback()
    }

    fun detach(target: GeckoSession) {
        if (session !== target) return
        photosActive = false
        lifecycle.detach(target)
        fallbackGeneration += 1L
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
            main.post {
                if (closed || !photosActive || generation != fallbackGeneration || !lifecycle.canUseFallback(attached, lifecycleGeneration)) {
                    decoded.recycle()
                    return@post
                }
                val previous = bitmap
                bitmap = decoded
                view.setImageBitmap(decoded)
                view.visibility = View.VISIBLE
                previous?.recycle()
            }
        }
    }

    fun hideFallback() {
        fallbackGeneration += 1L
        if (view.visibility != View.VISIBLE && bitmap == null) return
        view.visibility = View.GONE
        view.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
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
        hideFallback()
    }

    private fun onObserved(photo: FramePhotoBridge.PhotoObserved) {
        main.post {
            if (!closed && photosActive && lifecycle.canUseFallback(session, lifecycle.currentGeneration()) && bridge.isCurrentObservation(photo)) hideFallback()
        }
    }
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
