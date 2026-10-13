package com.wyattfleming.touchframe.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.wyattfleming.touchframe.TouchFrameActivity

/** The return control exists only while this app explicitly opens Android Settings. */
class TouchSettingsReturn(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var button: View? = null

    fun open(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        return try {
            if (button == null) {
                val control = Button(context).apply {
                    text = "Return to FrameOS Touch"
                    contentDescription = text
                    textSize = 20f
                    minHeight = (56 * resources.displayMetrics.density).toInt()
                    setOnClickListener {
                        // Keep the control available if Android cannot bring our task back.
                        runCatching {
                            context.startActivity(Intent(context, TouchFrameActivity::class.java).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            ))
                        }.onSuccess { remove() }
                    }
                }
                @Suppress("DEPRECATION")
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = (16 * context.resources.displayMetrics.density).toInt()
                    y = x
                }
                control.setOnApplyWindowInsetsListener { view, insets ->
                    // Settings can expose bars hidden by our immersive activity. Reposition
                    // after the overlay receives its own window insets, including cutouts.
                    @Suppress("DEPRECATION")
                    val right = insets.systemWindowInsetRight
                    @Suppress("DEPRECATION")
                    val bottom = insets.systemWindowInsetBottom
                    val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                    val gestures = if (Build.VERSION.SDK_INT >= 29) insets.mandatorySystemGestureInsets else null
                    val margin = (16 * context.resources.displayMetrics.density).toInt()
                    val x = margin + maxOf(right, cutout?.safeInsetRight ?: 0, gestures?.right ?: 0)
                    val y = margin + maxOf(bottom, cutout?.safeInsetBottom ?: 0, gestures?.bottom ?: 0)
                    if (params.x != x || params.y != y) {
                        params.x = x
                        params.y = y
                        if (view.isAttachedToWindow) manager.updateViewLayout(view, params)
                    }
                    insets
                }
                manager.addView(control, params)
                control.requestApplyInsets()
                button = control
            }
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: RuntimeException) {
            remove()
            false
        }
    }

    fun remove() {
        val current = button ?: return
        button = null
        runCatching { manager.removeViewImmediate(current) }
    }
}
