package com.wyattfleming.frameos

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.wyattfleming.frameos.navigation.FrameEffect
import com.wyattfleming.frameos.navigation.FrameIntent
import com.wyattfleming.frameos.navigation.FrameMode
import com.wyattfleming.frameos.navigation.FrameReducer
import com.wyattfleming.frameos.navigation.FrameState
import com.wyattfleming.frameos.navigation.PhysicalInputMapper
import com.wyattfleming.frameos.ui.DemoContentView
import com.wyattfleming.frameos.ui.dp
import kotlin.math.abs

class MainActivity : Activity() {
    private val reducer = FrameReducer()
    private val inputMapper = PhysicalInputMapper()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var content: DemoContentView
    private lateinit var hud: LinearLayout
    private lateinit var hudLabel: TextView
    private lateinit var hudPosition: TextView
    private lateinit var loading: LinearLayout

    private var state = FrameState()
    private var starLongPressed = false
    private var volumeUpPressed = false
    private var volumeDownPressed = false

    private val hideHud = Runnable {
        hud.animate()
            .alpha(0f)
            .setDuration(HUD_FADE_OUT_MILLIS)
            .withEndAction { hud.visibility = View.GONE }
            .start()
    }
    private val expireIdle = Runnable { dispatch(FrameIntent.IdleExpired) }

    private val gestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onFling(
                    start: MotionEvent?,
                    end: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (start == null) return false
                    val horizontalDistance = end.x - start.x
                    if (abs(horizontalDistance) < dp(96) || abs(velocityX) < abs(velocityY)) return false
                    dispatch(if (horizontalDistance < 0) FrameIntent.NextMode else FrameIntent.PreviousMode)
                    return true
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        enterImmersiveMode()
        render(state, announce = false)
        showStartupTransition()
        scheduleIdleReset()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            if (event.repeatCount == 0) {
                starLongPressed = false
                event.startTracking()
            }
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volumeUpPressed = true
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) volumeDownPressed = true
        if (volumeUpPressed && volumeDownPressed) {
            dispatch(FrameIntent.RestoreAutoBrightness)
            return true
        }

        val intent = inputMapper.mapKeyDown(keyCode = keyCode, scanCode = event.scanCode)
            ?: debugKeyboardIntent(keyCode)
        return if (intent != null) {
            dispatch(intent)
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_STAR) return super.onKeyLongPress(keyCode, event)
        starLongPressed = true
        dispatch(FrameIntent.GoHome)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_STAR -> {
                if (!starLongPressed) dispatch(FrameIntent.PrimaryAction)
                starLongPressed = false
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> volumeUpPressed = false
            KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownPressed = false
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.frame_dark))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { }
            setOnTouchListener { view, event ->
                val handled = gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP) view.performClick()
                handled
            }
        }
        content = DemoContentView(this)
        val fullScreenLayout = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            root.addView(content, fullScreenLayout)
        } else {
            root.addView(buildConfigurationView(), fullScreenLayout)
        }
        hud = buildModeHud()
        root.addView(
            hud,
            FrameLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.frame_hud_width),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.frame_safe_inset)
            },
        )
        loading = buildLoadingView()
        root.addView(
            loading,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.frame_safe_inset)
            },
        )
        setContentView(root)
        root.requestFocus()
    }

    private fun buildModeHud(): LinearLayout {
        hudLabel = TextView(this).apply {
            setTextColor(getColor(R.color.frame_ink))
            textSize = 24f
            typeface = Typeface.create("sans", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        hudPosition = TextView(this).apply {
            setTextColor(getColor(R.color.frame_ink_muted))
            textSize = 15f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = resources.getDimensionPixelSize(R.dimen.frame_hud_min_height)
            setPadding(
                resources.getDimensionPixelSize(R.dimen.frame_hud_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.frame_hud_padding_vertical),
                resources.getDimensionPixelSize(R.dimen.frame_hud_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.frame_hud_padding_vertical),
            )
            background = roundedBackground(0xB8181817.toInt(), strokeColor = 0x4DFFFFFF)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(hudLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(hudPosition, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
        }
    }

    private fun buildLoadingView(): LinearLayout {
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.frame_cobalt))
        }
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(textView(getString(R.string.starting_frameos), 20f, Color.WHITE, true))
            addView(textView(getString(R.string.starting_detail), 15f, getColor(R.color.frame_ink_muted)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(22), dp(12))
            background = roundedBackground(0xE0181817.toInt(), strokeColor = 0x4DFFFFFF)
            alpha = 0f
            visibility = View.INVISIBLE
            addView(spinner, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(14) })
            addView(words)
        }
    }

    private fun buildConfigurationView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(96), dp(72), dp(96), dp(72))
        setBackgroundColor(getColor(R.color.frame_paper))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = getString(R.string.configuration_title)
        addView(
            textView(
                text = getString(R.string.configuration_title),
                sizeSp = 40f,
                color = getColor(R.color.frame_dark),
                medium = true,
            ),
        )
        addView(
            textView(
                text = getString(R.string.configuration_detail),
                sizeSp = 24f,
                color = 0xFF444441.toInt(),
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            },
        )
        addView(
            textView(
                text = getString(R.string.configuration_footer),
                sizeSp = 18f,
                color = 0xFF73736F.toInt(),
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(34)
            },
        )
    }

    private fun showStartupTransition() {
        handler.postDelayed({
            loading.visibility = View.VISIBLE
            loading.animate().alpha(1f).setDuration(180).start()
        }, STARTUP_SPINNER_DELAY_MILLIS)
        handler.postDelayed({
            loading.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction { loading.visibility = View.GONE }
                .start()
            showHud()
        }, STARTUP_READY_MILLIS)
    }

    private fun dispatch(intent: FrameIntent) {
        val transition = reducer.reduce(state, intent)
        val modeChanged = transition.state.mode != state.mode
        state = transition.state
        applyBrightness(state.brightnessOverridePercent)
        render(state, announce = modeChanged)
        transition.effects.forEach(::applyEffect)
        scheduleIdleReset()
    }

    private fun render(next: FrameState, announce: Boolean) {
        content.mode = next.mode
        if (announce) content.animate().alpha(0.82f).setDuration(70).withEndAction {
            content.animate().alpha(1f).setDuration(180).start()
        }.start()
    }

    private fun applyEffect(effect: FrameEffect) {
        when (effect) {
            is FrameEffect.AnnounceMode -> showHud()
            is FrameEffect.AnnounceMessage -> showHud(effect.message)
        }
    }

    private fun showHud(message: String = state.mode.label) {
        val position = state.mode.ordinal
        hudLabel.text = message
        hudPosition.text = FrameMode.entries.indices.joinToString("  ") { index -> if (index == position) "●" else "○" }
        hud.contentDescription = "$message. ${position + 1} of ${FrameMode.entries.size}."
        hud.visibility = View.VISIBLE
        hud.alpha = 0f
        hud.translationY = -dp(8).toFloat()
        hud.animate()
            .alpha(1f)
            .translationY(0f)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(HUD_FADE_IN_MILLIS)
            .start()
        hud.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, HUD_VISIBLE_MILLIS)
    }

    private fun scheduleIdleReset() {
        handler.removeCallbacks(expireIdle)
        handler.postDelayed(expireIdle, IDLE_TIMEOUT_MILLIS)
    }

    private fun applyBrightness(percent: Int?) {
        val attributes = window.attributes
        attributes.screenBrightness = percent?.div(100f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    private fun debugKeyboardIntent(keyCode: Int): FrameIntent? {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> FrameIntent.NextMode
            KeyEvent.KEYCODE_DPAD_LEFT -> FrameIntent.PreviousMode
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> FrameIntent.PrimaryAction
            else -> null
        }
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun roundedBackground(color: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = resources.getDimension(R.dimen.frame_corner_radius)
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun textView(text: String, sizeSp: Float, color: Int, medium: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans", Typeface.BOLD)
        }

    private companion object {
        const val STARTUP_SPINNER_DELAY_MILLIS = 350L
        const val STARTUP_READY_MILLIS = 1_100L
        const val HUD_FADE_IN_MILLIS = 160L
        const val HUD_FADE_OUT_MILLIS = 220L
        const val HUD_VISIBLE_MILLIS = 1_500L
        const val IDLE_TIMEOUT_MILLIS = 15 * 60 * 1_000L
    }
}
