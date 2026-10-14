package com.wyattfleming.touchframe.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.wyattfleming.frameos.weather.LegacyIsrgRootTls
import com.wyattfleming.touchframe.R
import com.wyattfleming.touchframe.ui.TouchFonts
import com.wyattfleming.touchframe.ui.TouchKit
import com.wyattfleming.touchframe.ui.TouchPalette
import com.wyattfleming.touchframe.ui.TouchScale
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import org.mozilla.geckoview.GeckoRuntime
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Snapshot-only camera surface; it never claims to show live video. */
// Constructed by TouchFrameActivity with callbacks; never inflated from XML.
@SuppressLint("ViewConstructor")
class TouchCameraView(
    context: Context,
    origin: String,
    cameraIds: List<String>,
    private val tokenProvider: (WeatherRequestDeadline) -> String?,
    private val runtime: GeckoRuntime? = null,
) : FrameLayout(context) {
    var onSignIn: (() -> Unit)? = null
    private val policy = TouchCameraPolicy(origin, cameraIds)
    private val refreshGate = TouchCameraRefreshGate()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "touch-camera").apply { isDaemon = true }
    }
    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "touch-camera-watchdog").apply { isDaemon = true }
    }
    /** Only UI-originated cancellation uses this executor; worker cleanup disconnects inline. */
    private val disconnectWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "touch-camera-disconnect").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var night = false
    private val cameraIds = cameraIds
    private val cameraButtons = linkedMapOf<String, LinearLayout>()
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.touch_camera_snapshot_description)
    }
    private val mat = FrameLayout(context)
    private val emptyState = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private val caption = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var imageCamera: String? = null
    private var listSignedOut = false
    private var imageAtMillis: Long? = null
    private var stageState: Stage = Stage.LiveReady
    private var cameraVisible = false
    private var liveSurface: TouchLiveCameraSurface? = null

    /** What the snapshot area shows; network results are published into it on the main thread. */
    private sealed class Stage {
        object LiveReady : Stage()
        object Live : Stage()
        object Loading : Stage()
        object Image : Stage()
        object SignIn : Stage()
        data class Unavailable(val atMillis: Long) : Stage()
    }

    @Volatile private var selected = cameraIds.first()
    @Volatile private var connection: HttpURLConnection? = null
    private var periodic: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        mat.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mat.addView(emptyState, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rebuild()
    }

    /** Called by the activity clock so camera controls match the shell's day/night palette. */
    fun setNight(value: Boolean) {
        if (night == value) return
        night = value
        rebuild()
    }

    fun setActive(active: Boolean) {
        cameraVisible = active
        if (!active) {
            refreshGate.setActive(false, selected)
            periodic?.cancel(true)
            periodic = null
            disconnectFromUi()
            releaseLive()
            if (stageState == Stage.Live) stageState = Stage.LiveReady
        }
    }

    fun close() {
        if (!refreshGate.close()) return
        periodic?.cancel(true)
        periodic = null
        disconnectFromUi()
        releaseLive()
        worker.shutdownNow()
        watchdog.shutdownNow()
        // Shutdown allows the just-queued identity-checked disconnect to run.
        disconnectWorker.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    private fun requestRefresh() {
        if (!cameraVisible) return
        releaseLive()
        if (stageState == Stage.Live) stageState = Stage.Loading
        if (refreshGate.setActive(true, selected) || refreshGate.request(selected)) startWorkerIfNeeded()
        disconnectFromUi()
    }

    /** A periodic tick never preempts a fetch in flight; slow snapshots would otherwise never finish. */
    private fun requestPeriodicRefresh() {
        if (refreshGate.requestPeriodic(selected)) startWorkerIfNeeded()
    }

    private fun startWorkerIfNeeded() {
        runCatching { worker.execute(::drainRefreshes) }
    }

    /** A single worker drains only the newest requested camera; taps cannot queue network work. */
    private fun drainRefreshes() {
        while (true) {
            val request = refreshGate.next() ?: return
            fetch(request)
        }
    }

    private fun fetch(request: TouchCameraRefreshGate.Request) {
        if (!refreshGate.isCurrent(request)) return
        val deadline = WeatherRequestDeadline(timeoutMillis = TOTAL_TIMEOUT_MILLIS)
        val token = tokenProvider(deadline)
        // Token acquisition is caller-owned but no request may proceed after lifecycle cancellation.
        if (!refreshGate.isCurrent(request)) return
        if (token.isNullOrBlank()) return publish(request, null, SIGN_IN_REQUIRED, signInRequired = true)
        if (deadline.isExpired()) return publish(request, null, UNAVAILABLE, signInRequired = false)
        val url = policy.snapshotUrl(request.camera) ?: return
        var current: HttpURLConnection? = null
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            val opened = LegacyIsrgRootTls.ConnectionFactory().open(url)
            current = opened
            if (!refreshGate.isCurrent(request)) return
            connection = opened
            timeout = watchdog.schedule({
                if (refreshGate.isCurrent(request) && connection === opened) opened.disconnect()
            }, deadline.remainingMillis(), TimeUnit.MILLISECONDS)
            val millis = deadline.remainingMillis().coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            opened.requestMethod = "GET"
            opened.instanceFollowRedirects = false
            opened.useCaches = false
            opened.connectTimeout = millis
            opened.readTimeout = millis
            opened.setRequestProperty("Authorization", "Bearer $token")
            if (!refreshGate.isCurrent(request)) return
            val code = opened.responseCode
            if (code == 401 || code == 403) return publish(request, null, SIGN_IN_REQUIRED, signInRequired = true)
            if (code !in 200..299 || deadline.isExpired()) return publish(request, null, UNAVAILABLE, signInRequired = false)
            val bytes = opened.inputStream.use { boundedBytes(it, deadline, request) }
            if (!refreshGate.isCurrent(request)) return
            val bitmap = decodeSnapshot(bytes) ?: return publish(request, null, UNAVAILABLE, signInRequired = false)
            publish(request, bitmap, null, signInRequired = false)
        } catch (_: Exception) {
            if (refreshGate.isCurrent(request)) publish(request, null, UNAVAILABLE, signInRequired = false)
        } finally {
            timeout?.cancel(false)
            // This worker owns its request identity, so cleanup never targets a newer connection.
            if (connection === current) connection = null
            current?.disconnect()
        }
    }

    private fun publish(
        request: TouchCameraRefreshGate.Request,
        bitmap: android.graphics.Bitmap?,
        message: String?,
        signInRequired: Boolean,
    ) {
        main.post {
            if (!refreshGate.isCurrent(request)) return@post
            val now = System.currentTimeMillis()
            when {
                bitmap != null -> {
                    image.setImageBitmap(bitmap)
                    imageCamera = request.camera
                    imageAtMillis = now
                    stageState = Stage.Image
                }
                signInRequired -> stageState = Stage.SignIn
                // A failed refresh keeps a snapshot already showing for this camera and says how old it is.
                message != null -> if (imageCamera != request.camera) stageState = Stage.Unavailable(now) else lastFailureMillis = now
            }
            renderStage()
        }
    }

    private var lastFailureMillis: Long? = null

    private fun boundedBytes(
        input: java.io.InputStream,
        deadline: WeatherRequestDeadline,
        request: TouchCameraRefreshGate.Request,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            require(refreshGate.isCurrent(request) && !deadline.isExpired())
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeSnapshot(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = TouchCameraPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            })
        } catch (_: OutOfMemoryError) {
            null
        }
        if (bitmap != null && (bitmap.width > 1920 || bitmap.height > 1080)) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /** Never call disconnect on the UI thread; a rejected task after shutdown is harmless. */
    private fun disconnectFromUi() {
        val observed = connection ?: return
        runCatching {
            disconnectWorker.execute {
                if (connection === observed) {
                    observed.disconnect()
                    if (connection === observed) connection = null
                }
            }
        }
    }

    private fun selectCamera(camera: String) {
        if (camera == selected) return requestRefresh()
        refreshGate.setActive(false, selected)
        releaseLive()
        selected = camera
        image.setImageDrawable(null)
        imageCamera = null
        imageAtMillis = null
        lastFailureMillis = null
        stageState = Stage.LiveReady
        rebuild()
        requestRefresh()
    }

    private fun kit() = TouchKit(context, TouchPalette.forNight(night), TouchScale.forScreen(resources.configuration))

    private var builtScale: TouchScale? = null

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (builtScale != null && TouchScale.forScreen(resources.configuration) != builtScale) post { rebuild() }
    }

    /** Builds the stage, caption and camera list; network state lands in [renderStage]. */
    private fun rebuild() {
        val kit = kit()
        builtScale = kit.scale
        val palette = TouchCameraPalette.forNight(night)
        val p = kit.palette
        removeAllViews()
        (mat.parent as? android.view.ViewGroup)?.removeView(mat)
        (caption.parent as? android.view.ViewGroup)?.removeView(caption)
        (list.parent as? android.view.ViewGroup)?.removeView(list)
        setBackgroundColor(palette.surface)
        mat.background = kit.rounded(palette.panel, kit.scale.radius, p.line)
        val inset = kit.dp(16)
        mat.setPadding(inset, inset, inset, inset)
        image.setBackgroundColor(p.letterbox)
        val stage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(mat, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            addView(caption, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(16) })
        }
        list.removeAllViews()
        cameraButtons.clear()
        val wall = kit.scale == TouchScale.WALL
        listSignedOut = stageState == Stage.SignIn
        cameraIds.forEach { id ->
            val button = cameraButton(kit, palette, id, id == selected && !listSignedOut, enabled = !listSignedOut)
            cameraButtons[id] = button
            list.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, kit.dp(if (wall) 88 else 76)).apply {
                if (list.childCount > 0) topMargin = kit.dp(12)
            })
        }
        list.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        list.addView(kit.button(context.getString(R.string.touch_camera_snapshot), TouchKit.Emphasis.SECONDARY, iconRes = R.drawable.ic_touch_refresh) { requestRefresh() }.apply {
            tag = REFRESH_TAG
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, kit.dp(kit.scale.target)))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = kit.dp(kit.scale.pad)
            setPadding(pad, (pad * 0.6f).toInt(), pad, (pad * 0.5f).toInt())
            addView(stage, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(list, LinearLayout.LayoutParams(kit.dp(if (wall) 340 else 270), LayoutParams.MATCH_PARENT).apply { marginStart = kit.dp(kit.scale.gap) })
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderStage()
    }

    private fun cameraButton(kit: TouchKit, palette: TouchCameraPalette, id: String, isSelected: Boolean, enabled: Boolean) = LinearLayout(context).apply {
        val p = kit.palette
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dp(20), 0, kit.dp(16), 0)
        isClickable = enabled
        isFocusable = enabled
        isEnabled = enabled
        this.isSelected = isSelected
        val label = TouchCameraLabels.forEntityId(id)
        contentDescription = label
        val ink = when {
            !enabled -> palette.disabledInk
            isSelected -> palette.accentInk
            else -> palette.ink
        }
        background = when {
            !enabled -> kit.rounded(0x00000000, kit.scale.radiusSmall, palette.disabledInk, 2, dashed = true)
            isSelected -> kit.pressable(kit.rounded(palette.accent, kit.scale.radiusSmall, p.accentStroke, 2), kit.scale.radiusSmall)
            else -> kit.pressable(kit.rounded(p.matRaised, kit.scale.radiusSmall, p.stroke, 2), kit.scale.radiusSmall)
        }
        addView(kit.icon(R.drawable.ic_touch_cameras, ink, 30))
        addView(kit.text(label, kit.scale.body, TouchFonts.Face.TEXT_BOLD, ink, maxLines = 2).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = kit.dp(14) })
        if (enabled) setOnClickListener { selectCamera(id) }
    }

    /** Applies the current stage without rebuilding the controls (snapshots arrive every few seconds). */
    private fun renderStage() {
        // Signing out disables the list (and signing back in restores it); rebuild once per change.
        if ((stageState == Stage.SignIn) != listSignedOut) return rebuild()
        val kit = kit()
        val p = kit.palette
        val name = TouchCameraLabels.forEntityId(selected)
        val showImage = stageState == Stage.Image && imageCamera == selected
        image.visibility = if (showImage) View.VISIBLE else View.INVISIBLE
        val showLive = stageState == Stage.Live
        emptyState.visibility = if (showImage || showLive) View.GONE else View.VISIBLE
        list.findViewWithTag<View>(REFRESH_TAG)?.visibility = if (showLive) View.GONE else View.VISIBLE
        emptyState.removeAllViews()
        when (val state = stageState) {
            Stage.LiveReady -> fillEmpty(kit, context.getString(R.string.touch_camera_live_title, name),
                context.getString(R.string.touch_camera_live_detail), context.getString(R.string.touch_camera_watch_live), TouchKit.Emphasis.PRIMARY) { watchLive() }
            Stage.Live -> Unit
            Stage.Image -> Unit
            Stage.Loading -> emptyState.addView(kit.text(context.getString(R.string.touch_camera_loading, name), kit.scale.body, color = p.inkMuted))
            Stage.SignIn -> fillEmpty(kit, context.getString(R.string.touch_camera_sign_in_required),
                context.getString(R.string.touch_camera_sign_in_detail), context.getString(R.string.touch_camera_sign_in), TouchKit.Emphasis.PRIMARY) { onSignIn?.invoke() }
            is Stage.Unavailable -> fillEmpty(kit, context.getString(R.string.touch_camera_unavailable, name),
                context.getString(R.string.touch_camera_unavailable_detail, timeText(state.atMillis)), context.getString(R.string.touch_camera_try_again),
                TouchKit.Emphasis.SECONDARY) { requestRefresh() }
        }
        caption.removeAllViews()
        caption.addView(kit.text(name, kit.scale.title, TouchFonts.Face.DISPLAY_BOLD, maxLines = 1))
        val detail = when {
            !showImage -> null
            lastFailureMillis != null && (imageAtMillis ?: 0L) < lastFailureMillis!! ->
                context.getString(R.string.touch_camera_stale, timeText(imageAtMillis ?: 0L))
            else -> imageAtMillis?.let { context.getString(R.string.touch_camera_updated, timeText(it)) }
        }
        detail?.let {
            caption.addView(kit.text(it, kit.scale.label, color = if (lastFailureMillis != null && (imageAtMillis ?: 0L) < lastFailureMillis!!) p.pendingInk else p.inkMuted),
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = kit.dp(16) })
        }
    }

    /** The native panel asks for video only after an explicit tap; snapshots remain an optional fallback. */
    private fun watchLive() {
        val engine = runtime ?: run {
            stageState = Stage.Unavailable(System.currentTimeMillis())
            renderStage()
            return
        }
        val surface = liveSurface ?: TouchLiveCameraSurface(context, engine, policyOrigin(), cameraIds, tokenProvider) { status ->
            main.post {
                if (status == TouchLiveCameraBridge.Status.FAILED && stageState == Stage.Live) {
                    stageState = Stage.LiveReady
                    releaseLive()
                    renderStage()
                }
            }
        }.also { created ->
            liveSurface = created
            mat.addView(created, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        stageState = Stage.Live
        surface.start(selected)
        renderStage()
    }

    private fun policyOrigin(): String = policy.snapshotUrl(selected)?.let { "${it.scheme}://${it.authority}" }
        ?: throw IllegalStateException("Configured camera origin is unavailable")

    /** A live camera owns a peer/socket/session; hiding it is never a passive View visibility change. */
    private fun releaseLive() {
        val surface = liveSurface ?: return
        liveSurface = null
        (surface.parent as? android.view.ViewGroup)?.removeView(surface)
        surface.close()
    }

    private fun fillEmpty(kit: TouchKit, title: String, detail: String, action: String, emphasis: TouchKit.Emphasis, onAction: () -> Unit) {
        val p = kit.palette
        emptyState.addView(kit.icon(R.drawable.ic_touch_cameras, p.inkMuted, 64))
        emptyState.addView(kit.text(title, kit.scale.headline, TouchFonts.Face.DISPLAY_BOLD).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(16) })
        emptyState.addView(kit.text(detail, kit.scale.body, color = p.inkMuted).apply { gravity = Gravity.CENTER; maxWidth = kit.dp(620) },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dp(10) })
        emptyState.addView(kit.button(action, emphasis, iconRes = if (emphasis == TouchKit.Emphasis.SECONDARY) R.drawable.ic_touch_refresh else null) { onAction() },
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, kit.dp(kit.scale.target)).apply { topMargin = kit.dp(20) })
    }

    private fun timeText(millis: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val TOTAL_TIMEOUT_MILLIS = 8_000L
        const val REFRESH_TAG = "touch-camera-refresh"
        // Worker-side outcome markers; the main thread chooses the visible wording.
        const val SIGN_IN_REQUIRED = "sign-in-required"
        const val UNAVAILABLE = "unavailable"
    }
}

/** Humanizes a configured entity ID without changing the ID used for snapshot requests. */
internal object TouchCameraLabels {
    fun forEntityId(id: String): String {
        val tokens = id.removePrefix("camera.").split('_').filter(String::isNotBlank)
        val collapsed = collapseImmediatelyRepeatedLeadingTokens(tokens)
        return collapsed.joinToString(" ").replaceFirstChar { it.titlecase() }
    }

    private fun collapseImmediatelyRepeatedLeadingTokens(tokens: List<String>): List<String> {
        val repeatedPrefixLength = (1..tokens.size / 2).firstOrNull { prefixLength ->
            tokens.take(prefixLength) == tokens.drop(prefixLength).take(prefixLength)
        } ?: return tokens
        return tokens.take(repeatedPrefixLength) + tokens.drop(repeatedPrefixLength * 2)
    }
}

/** Thread-safe latest-only gate for camera refreshes, independent of Android I/O. */
internal class TouchCameraRefreshGate {
    data class Request(val camera: String, val generation: Long)

    private var active = false
    private var closed = false
    private var inFlight = false
    private var pendingCamera: String? = null
    private var generation = 0L

    @Synchronized
    fun setActive(nextActive: Boolean, camera: String): Boolean {
        if (closed || active == nextActive) return false
        active = nextActive
        generation += 1
        if (!nextActive) {
            pendingCamera = null
            return false
        }
        pendingCamera = camera
        return scheduleIfIdle()
    }

    @Synchronized
    fun request(camera: String): Boolean {
        if (closed || !active) return false
        generation += 1
        pendingCamera = camera
        return scheduleIfIdle()
    }

    /** Like [request] but a no-op while any fetch is in flight, so the in-flight request stays current. */
    @Synchronized
    fun requestPeriodic(camera: String): Boolean {
        if (closed || !active || inFlight) return false
        return request(camera)
    }

    @Synchronized
    fun next(): Request? {
        if (closed || !active) {
            inFlight = false
            return null
        }
        val camera = pendingCamera ?: run {
            inFlight = false
            return null
        }
        pendingCamera = null
        return Request(camera, generation)
    }

    @Synchronized
    fun isCurrent(request: Request): Boolean = !closed && active && request.generation == generation

    @Synchronized
    fun close(): Boolean {
        if (closed) return false
        closed = true
        active = false
        pendingCamera = null
        generation += 1
        return true
    }

    @Synchronized
    private fun scheduleIfIdle(): Boolean = if (inFlight) false else true.also { inFlight = true }
}

internal class TouchCameraPolicy(origin: String, cameraIds: List<String>) {
    private val origin = URI(origin).also { require(it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null && it.query == null && it.fragment == null) }
    private val cameras = cameraIds.also { require(it.size in 1..4 && it.distinct().size == it.size && it.all(::validCamera)) }
    fun snapshotUrl(camera: String): URI? = camera.takeIf(cameras::contains)?.let { origin.resolve("/api/camera_proxy/$it") }
    companion object {
        private val CAMERA = Regex("camera\\.[a-z0-9_]+")
        fun validCamera(value: String) = CAMERA.matches(value)
        fun sampleSize(width: Int, height: Int): Int { var sample = 1; while ((width + sample - 1) / sample > 1920 || (height + sample - 1) / sample > 1080) sample *= 2; return sample }
    }
}

/** Camera colors are a projection of the shared [TouchPalette], so Cameras never drifts from the shell. */
internal data class TouchCameraPalette(
    val surface: Int,
    val panel: Int,
    val ink: Int,
    val accent: Int,
    val accentInk: Int,
    val disabledSurface: Int,
    val disabledInk: Int,
) {
    companion object {
        fun contrastRatio(foreground: Int, background: Int): Double = TouchPalette.contrastRatio(foreground, background)

        fun forNight(night: Boolean): TouchCameraPalette {
            val theme = TouchPalette.forNight(night)
            return TouchCameraPalette(
                surface = theme.wall, panel = theme.mat, ink = theme.ink,
                accent = theme.accent, accentInk = theme.onAccent,
                disabledSurface = theme.wall, disabledInk = theme.disabledInk,
            )
        }
    }
}
