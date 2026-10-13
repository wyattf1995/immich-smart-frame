package com.wyattfleming.frameos.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.wyattfleming.frameos.MainActivity

/** A return affordance that exists only during an explicit external Settings visit. */
class FrameExternalSettingsReturnController(private val adapter: Adapter) {
    interface Adapter {
        fun canDrawOverlays(): Boolean
        fun showReturn(onReturn: () -> Unit): Boolean
        fun removeReturn()
        fun launchAndroidSettings(): Boolean
        fun launchFrame(): Boolean
    }

    enum class Outcome { LAUNCHED, OVERLAY_PERMISSION_DENIED, EXTERNAL_LAUNCH_FAILED }
    private var active = false

    fun openSettings(): Outcome {
        if (!adapter.canDrawOverlays()) return Outcome.OVERLAY_PERMISSION_DENIED
        if (!adapter.showReturn(::returnToFrame)) return Outcome.OVERLAY_PERMISSION_DENIED
        active = true
        if (adapter.launchAndroidSettings()) return Outcome.LAUNCHED
        remove()
        return Outcome.EXTERNAL_LAUNCH_FAILED
    }

    fun onResume() = remove()
    fun onDestroy() = remove()

    private fun returnToFrame() {
        // Retain the affordance when launch fails; the user can retry instead of being
        // stranded in the vendor Settings activity.
        if (adapter.launchFrame()) remove()
    }

    private fun remove() {
        if (!active) return
        active = false
        adapter.removeReturn()
    }
}

/** Android-only adapter. It creates one non-focusable, button-sized application overlay. */
class AndroidFrameExternalSettingsReturnAdapter(private val context: Context) : FrameExternalSettingsReturnController.Adapter {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var returnView: View? = null

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun showReturn(onReturn: () -> Unit): Boolean = runCatching {
        if (returnView != null) return true
        val density = context.resources.displayMetrics.density
        val button = Button(context).apply {
            text = "Return to FrameOS"
            contentDescription = text
            minHeight = (48 * density).toInt()
            minWidth = (160 * density).toInt()
            setOnClickListener { onReturn() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            FrameExternalSettingsOverlayWindowType.forApi(Build.VERSION.SDK_INT),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (16 * density).toInt()
            y = (16 * density).toInt()
        }
        windowManager.addView(button, params)
        returnView = button
        true
    }.getOrDefault(false)

    override fun removeReturn() {
        val view = returnView ?: return
        returnView = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    override fun launchAndroidSettings(): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    override fun launchFrame(): Boolean = runCatching {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
        true
    }.getOrDefault(false)
}

/** Chooses the system-supported overlay type without broadening the overlay permission scope. */
internal object FrameExternalSettingsOverlayWindowType {
    @Suppress("DEPRECATION")
    const val PHONE: Int = WindowManager.LayoutParams.TYPE_PHONE

    fun forApi(apiLevel: Int): Int =
        if (apiLevel >= Build.VERSION_CODES.O) applicationOverlayForAndroid8OrLater() else PHONE

    /** The Android 8 overlay value is never evaluated on Android 6 or 7. */
    @android.annotation.TargetApi(Build.VERSION_CODES.O)
    private fun applicationOverlayForAndroid8OrLater(): Int =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
}
