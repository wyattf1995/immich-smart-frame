package com.wyattfleming.touchframe.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wyattfleming.frameos.weather.LegacyIsrgRootTls
import com.wyattfleming.touchframe.R
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
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
    private lateinit var content: LinearLayout
    private lateinit var cameras: LinearLayout
    private val cameraButtons = linkedMapOf<String, TextView>()
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.touch_camera_snapshot_description)
    }
    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }
    private val refresh = TextView(context).apply {
        text = context.getString(R.string.touch_camera_refresh)
        gravity = Gravity.CENTER
        minimumHeight = context.dp(56)
        isClickable = true
        isFocusable = true
        setOnClickListener { requestRefresh() }
    }
    private val signIn = TextView(context).apply {
        text = context.getString(R.string.touch_camera_sign_in)
        gravity = Gravity.CENTER
        minimumHeight = context.dp(56)
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        setOnClickListener { onSignIn?.invoke() }
    }
    private var selected = cameraIds.first()
    @Volatile private var connection: HttpURLConnection? = null
    private var periodic: java.util.concurrent.ScheduledFuture<*>? = null

    init {
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        }
        cameras = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cameraIds.forEach { id ->
            val button = button(cameraLabel(id)) { selectCamera(id) }
            cameraButtons[id] = button
            cameras.addView(button, LinearLayout.LayoutParams(0, context.dp(56), 1f))
        }
        content.addView(cameras)
        content.addView(image, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(refresh, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(56)))
        content.addView(signIn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(56)))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        status.text = context.getString(R.string.touch_camera_unavailable)
        applyPalette()
    }

    /** Called by the activity clock so camera controls match the shell's day/night palette. */
    fun setNight(value: Boolean) {
        if (night == value) return
        night = value
        applyPalette()
    }

    fun setActive(active: Boolean) {
        if (refreshGate.setActive(active, selected)) startWorkerIfNeeded()
        if (!active) {
            periodic?.cancel(true)
            periodic = null
            disconnectFromUi()
        } else if (periodic == null) {
            periodic = watchdog.scheduleWithFixedDelay({ requestRefresh() }, 5, 5, TimeUnit.SECONDS)
        }
    }

    fun close() {
        if (!refreshGate.close()) return
        periodic?.cancel(true)
        periodic = null
        disconnectFromUi()
        worker.shutdownNow()
        watchdog.shutdownNow()
        // Shutdown allows the just-queued identity-checked disconnect to run.
        disconnectWorker.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    private fun requestRefresh() {
        if (refreshGate.request(selected)) startWorkerIfNeeded()
        disconnectFromUi()
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
        if (token.isNullOrBlank()) return publish(request, null, context.getString(R.string.touch_camera_sign_in_required), signInRequired = true)
        if (deadline.isExpired()) return publish(request, null, context.getString(R.string.touch_camera_unavailable), signInRequired = false)
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
            if (code == 401 || code == 403) return publish(request, null, context.getString(R.string.touch_camera_sign_in_required), signInRequired = true)
            if (code !in 200..299 || deadline.isExpired()) return publish(request, null, context.getString(R.string.touch_camera_unavailable), signInRequired = false)
            val bytes = opened.inputStream.use { boundedBytes(it, deadline, request) }
            if (!refreshGate.isCurrent(request)) return
            val bitmap = decodeSnapshot(bytes) ?: return publish(request, null, context.getString(R.string.touch_camera_unavailable), signInRequired = false)
            publish(request, bitmap, null, signInRequired = false)
        } catch (_: Exception) {
            if (refreshGate.isCurrent(request)) publish(request, null, context.getString(R.string.touch_camera_unavailable), signInRequired = false)
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
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                status.text = context.getString(R.string.touch_camera_updated, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()))
                signIn.visibility = View.GONE
            } else if (message != null) {
                status.text = message
                signIn.visibility = if (signInRequired) View.VISIBLE else View.GONE
            }
        }
    }

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
        selected = camera
        applyPalette()
        requestRefresh()
    }

    private fun button(text: String, action: () -> Unit) = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(56)
        setOnClickListener { action() }
    }

    private fun applyPalette() {
        val palette = TouchCameraPalette.forNight(night)
        setBackgroundColor(palette.surface)
        content.setBackgroundColor(palette.surface)
        cameras.setBackgroundColor(palette.surface)
        image.setBackgroundColor(palette.panel)
        status.setTextColor(palette.ink)
        cameraButtons.forEach { (id, button) ->
            button.isSelected = id == selected
            styleControl(button, palette, selected = button.isSelected, action = false)
        }
        styleControl(refresh, palette, selected = true, action = true)
        styleControl(signIn, palette, selected = true, action = true)
    }

    private fun styleControl(view: TextView, palette: TouchCameraPalette, selected: Boolean, action: Boolean) {
        view.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.disabledInk, if (selected || action) palette.accentInk else palette.ink),
        ))
        view.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(palette.disabledSurface))
            addState(intArrayOf(), rounded(if (selected || action) palette.accent else palette.panel))
        }
        view.setPadding(context.dp(12), 0, context.dp(12), 0)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(12).toFloat()
        setColor(color)
    }

    private fun cameraLabel(id: String) = id.removePrefix("camera.").replace('_', ' ').replaceFirstChar { it.titlecase() }
    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val TOTAL_TIMEOUT_MILLIS = 8_000L
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

/** Explicit API23-safe colors keep Camera controls readable on either shell palette. */
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
        fun contrastRatio(foreground: Int, background: Int): Double {
            fun channel(value: Int): Double {
                val normalized = value / 255.0
                return if (normalized <= 0.04045) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
            fun luminance(color: Int): Double =
                0.2126 * channel((color shr 16) and 0xff) +
                    0.7152 * channel((color shr 8) and 0xff) +
                    0.0722 * channel(color and 0xff)
            val first = luminance(foreground)
            val second = luminance(background)
            return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
        }

        fun forNight(night: Boolean): TouchCameraPalette = if (night) {
            TouchCameraPalette(
                surface = 0xFF101722.toInt(), panel = 0xFF172536.toInt(), ink = 0xFFF1EDE4.toInt(),
                accent = 0xFF4F9F9A.toInt(), accentInk = 0xFF101722.toInt(),
                disabledSurface = 0xFF27313C.toInt(), disabledInk = 0xFFA9B2B8.toInt(),
            )
        } else {
            TouchCameraPalette(
                surface = 0xFFF1EDE4.toInt(), panel = 0xFFE3E6DF.toInt(), ink = 0xFF182630.toInt(),
                accent = 0xFF4F9F9A.toInt(), accentInk = 0xFF101722.toInt(),
                disabledSurface = 0xFFD7D5CE.toInt(), disabledInk = 0xFF48555B.toInt(),
            )
        }
    }
}
