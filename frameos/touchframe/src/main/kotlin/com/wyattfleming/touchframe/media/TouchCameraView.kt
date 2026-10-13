package com.wyattfleming.touchframe.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
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
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Snapshot-only camera surface; it never claims to show live video. */
class TouchCameraView(
    context: Context,
    origin: String,
    cameraIds: List<String>,
    private val tokenProvider: (WeatherRequestDeadline) -> String?,
) : FrameLayout(context) {
    var onSignIn: (() -> Unit)? = null
    private val policy = TouchCameraPolicy(origin, cameraIds)
    private val worker = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "touch-camera").apply { isDaemon = true } }
    private val cancellation = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "touch-camera-cancel").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Camera snapshot"; setBackgroundColor(Color.rgb(245, 242, 233)) }
    private val status = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER; setTextColor(Color.rgb(18, 36, 43)) }
    private val refresh = TextView(context).apply { text = "Refresh"; gravity = Gravity.CENTER; minimumHeight = context.dp(56); isClickable = true; isFocusable = true; setOnClickListener { requestRefresh() } }
    private var selected = cameraIds.first()
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var connection: HttpURLConnection? = null
    private var periodic: java.util.concurrent.ScheduledFuture<*>? = null
    private var signInNotified = false

    init {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16)) }
        val cameras = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cameraIds.forEach { id -> cameras.addView(button(cameraLabel(id)) { selected = id; requestRefresh() }, LinearLayout.LayoutParams(0, context.dp(56), 1f)) }
        content.addView(cameras)
        content.addView(image, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(refresh, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(56)))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        status.text = "Snapshot unavailable"
    }

    fun setActive(active: Boolean) {
        if (closed || this.active == active) return
        this.active = active
        generation += 1
        periodic?.cancel(true); periodic = null
        cancelConnection()
        if (active) periodic = worker.scheduleWithFixedDelay({ requestRefresh() }, 0, 5, TimeUnit.SECONDS)
    }

    fun close() {
        if (closed) return
        closed = true; active = false; generation += 1
        periodic?.cancel(true); cancelConnection()
        worker.shutdownNow(); cancellation.shutdownNow(); main.removeCallbacksAndMessages(null)
    }

    private fun requestRefresh() {
        if (!active || closed) return
        val requestGeneration = ++generation
        cancelConnection()
        val camera = selected
        worker.execute { fetch(camera, requestGeneration) }
    }

    private fun fetch(camera: String, requestGeneration: Long) {
        val deadline = WeatherRequestDeadline(timeoutMillis = TOTAL_TIMEOUT_MILLIS)
        val token = tokenProvider(deadline) ?: return publish(requestGeneration, null, "Sign in required", true)
        if (deadline.isExpired()) return publish(requestGeneration, null, "Snapshot unavailable", false)
        val url = policy.snapshotUrl(camera) ?: return
        var current: HttpURLConnection? = null
        var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            current = LegacyIsrgRootTls.ConnectionFactory().open(url)
            connection = current
            watchdog = cancellation.schedule({
                if (connection === current && requestGeneration == generation) current?.disconnect()
            }, deadline.remainingMillis(), TimeUnit.MILLISECONDS)
            val timeout = deadline.remainingMillis().coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            current!!.requestMethod = "GET"; current!!.instanceFollowRedirects = false; current!!.useCaches = false
            current!!.connectTimeout = timeout; current!!.readTimeout = timeout
            current!!.setRequestProperty("Authorization", "Bearer $token")
            val code = current!!.responseCode
            if (code == 401 || code == 403) return publish(requestGeneration, null, "Sign in required", true)
            if (code !in 200..299 || deadline.isExpired()) return publish(requestGeneration, null, "Snapshot unavailable", false)
            val bytes = current!!.inputStream.use { boundedBytes(it, deadline) }
            val bitmap = decodeSnapshot(bytes) ?: return publish(requestGeneration, null, "Snapshot unavailable", false)
            publish(requestGeneration, bitmap, null, false)
        } catch (_: Exception) {
            publish(requestGeneration, null, "Snapshot unavailable", false)
        } finally {
            watchdog?.cancel(false)
            current?.let(::cancelConnection)
        }
    }

    private fun publish(requestGeneration: Long, bitmap: android.graphics.Bitmap?, message: String?, signIn: Boolean) = main.post {
        if (closed || !active || requestGeneration != generation) return@post
        if (bitmap != null) { image.setImageBitmap(bitmap); status.text = "Snapshot updated ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}"; signInNotified = false }
        else if (message != null) status.text = message
        if (signIn && !signInNotified) { signInNotified = true; onSignIn?.invoke() }
    }

    private fun boundedBytes(input: java.io.InputStream, deadline: WeatherRequestDeadline): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
        while (true) { require(!deadline.isExpired()); val count = input.read(buffer); if (count < 0) break; total += count; require(total <= MAX_BYTES); output.write(buffer, 0, count) }
        return output.toByteArray()
    }

    private fun decodeSnapshot(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = TouchCameraPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
        val bitmap = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }) } catch (_: OutOfMemoryError) { null }
        if (bitmap != null && (bitmap.width > 1920 || bitmap.height > 1080)) { bitmap.recycle(); return null }
        return bitmap
    }
    private fun cancelConnection(expected: HttpURLConnection? = null) {
        val observed = connection ?: return
        if (expected != null && observed !== expected) return
        cancellation.execute { observed.disconnect(); if (connection === observed) connection = null }
    }
    private fun button(text: String, action: () -> Unit) = TextView(context).apply { this.text = text; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; minimumHeight = context.dp(56); setOnClickListener { action() } }
    private fun cameraLabel(id: String) = id.removePrefix("camera.").replace('_', ' ').replaceFirstChar { it.titlecase() }
    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private companion object { const val MAX_BYTES = 8 * 1024 * 1024; const val TOTAL_TIMEOUT_MILLIS = 8_000L }
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

