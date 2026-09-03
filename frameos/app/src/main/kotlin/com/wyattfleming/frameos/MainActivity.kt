package com.wyattfleming.frameos

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.wyattfleming.frameos.navigation.ContextualFrameAction
import com.wyattfleming.frameos.navigation.ContextualInputMapper
import com.wyattfleming.frameos.navigation.FrameEffect
import com.wyattfleming.frameos.navigation.FrameIntent
import com.wyattfleming.frameos.navigation.FrameMode
import com.wyattfleming.frameos.navigation.FrameModeAvailability
import com.wyattfleming.frameos.navigation.FrameReducer
import com.wyattfleming.frameos.navigation.FrameState
import com.wyattfleming.frameos.navigation.FrameStartupPolicy
import com.wyattfleming.frameos.navigation.ModeGestureBurst
import com.wyattfleming.frameos.navigation.ModeGestureDirection
import com.wyattfleming.frameos.navigation.PhysicalInputMapper
import com.wyattfleming.frameos.navigation.VolumeButton
import com.wyattfleming.frameos.navigation.VolumeInputEffect
import com.wyattfleming.frameos.navigation.VolumeInputState
import com.wyattfleming.frameos.auth.AndroidKeystoreOAuthSessionStore
import com.wyattfleming.frameos.auth.HomeAssistantOAuthClient
import com.wyattfleming.frameos.auth.HomeAssistantOAuthEndpoint
import com.wyattfleming.frameos.auth.OAuthCallbackVerifier
import com.wyattfleming.frameos.auth.OAuthState
import com.wyattfleming.frameos.auth.PendingOAuthStateStore
import com.wyattfleming.frameos.auth.SharedPreferencesPendingOAuthStateStore
import com.wyattfleming.frameos.boot.FrameBootRecoveryScheduler
import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.config.FrameConfigurationStore
import com.wyattfleming.frameos.control.FrameControlCommand
import com.wyattfleming.frameos.control.FrameControlContract
import com.wyattfleming.frameos.control.FrameControlStore
import com.wyattfleming.frameos.security.FrameExternalControlPolicy
import com.wyattfleming.frameos.ui.WeatherContentView
import com.wyattfleming.frameos.ui.dp
import com.wyattfleming.frameos.weather.HomeAssistantWeatherEndpoint
import com.wyattfleming.frameos.weather.HomeAssistantWeatherParser
import com.wyattfleming.frameos.weather.HomeAssistantWeatherRemote
import com.wyattfleming.frameos.weather.CancellableHomeAssistantHttpTransport
import com.wyattfleming.frameos.weather.SharedPreferencesWeatherCache
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherCoordinator
import com.wyattfleming.frameos.weather.WeatherPresentation
import com.wyattfleming.frameos.weather.WeatherPresenter
import com.wyattfleming.frameos.weather.WeatherRefreshPolicy
import com.wyattfleming.frameos.weather.WeatherRepository
import com.wyattfleming.frameos.weather.WeatherRequestDeadline
import com.wyattfleming.frameos.web.FrameSurfaceRouter
import com.wyattfleming.frameos.web.FrameSurfaceTarget
import com.wyattfleming.frameos.web.FrameWebCompositionPolicy
import com.wyattfleming.frameos.web.FrameWebSlot
import com.wyattfleming.frameos.web.FrameWebSurface
import org.mozilla.geckoview.GeckoView
import java.net.URI
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

class MainActivity : Activity() {
    private val reducer = FrameReducer { FrameModeAvailability(configuration?.birdsUrl).cycleModes }
    private val inputMapper = PhysicalInputMapper()
    private val contextualInputMapper = ContextualInputMapper()
    private val handler = Handler(Looper.getMainLooper())
    private val modeGestureBurst = ModeGestureBurst()
    private val weatherWorkerExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val weatherRequestExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val authorizationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val secureRandom = SecureRandom()
    private val operationLogger: FrameOperationLogger = AndroidFrameOperationLogger()
    private val externalControlPolicy by lazy {
        FrameExternalControlPolicy(
            debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
    }

    private lateinit var root: FrameLayout
    private lateinit var weatherContent: WeatherContentView
    private lateinit var hud: LinearLayout
    private lateinit var hudLabel: TextView
    private lateinit var hudPosition: TextView
    private lateinit var loading: LinearLayout
    private lateinit var webRecovery: LinearLayout
    private lateinit var webRecoveryLabel: TextView

    private var state = FrameStartupPolicy.initialState()
    private var starLongPressed = false
    private val volumeInput = VolumeInputState()
    private lateinit var configurationStore: FrameConfigurationStore
    private lateinit var controlStore: FrameControlStore
    private var configuration: FrameConfiguration? = null
    private var oauthClient: HomeAssistantOAuthClient? = null
    private var oauthEndpoint: HomeAssistantOAuthEndpoint? = null
    private var oauthCallbackPageUrl: String? = null
    private var pendingOAuthStateStore: PendingOAuthStateStore? = null
    private var callbackVerifier: OAuthCallbackVerifier? = null
    private var weatherCoordinator: WeatherCoordinator? = null
    private var weatherCache: SharedPreferencesWeatherCache? = null
    private var weatherNeedsAuthentication = false
    private var weatherRefreshInProgress = false
    private var weatherAuthorizationInProgress = false
    private var activityResumed = false
    private var weatherRequestGeneration = 0
    private var homeAssistantTransport: CancellableHomeAssistantHttpTransport? = null
    private var weatherRefreshFuture: Future<*>? = null
    private var weatherRefreshStartedAtUptimeMillis = 0L
    private var authorizationFuture: Future<*>? = null
    private var authorizationStartedAtUptimeMillis = 0L
    private var pendingAuthorizationCode: String? = null
    private var pendingAuthorizationPreviousAuthEpoch: String? = null
    private var surfaceRouter: FrameSurfaceRouter? = null
    private var webSurface: FrameWebSurface? = null
    private val slowPageLoads = mutableMapOf<String, Runnable>()
    private var bootRecoveryConfirmed = false
    private var pendingBootRecoveryDraw: ViewTreeObserver.OnDrawListener? = null
    private var pendingBootRecoveryObserver: ViewTreeObserver? = null
    private var pendingBootRecoveryWebLabel: String? = null

    private val commitModeGesture = Runnable {
        modeGestureBurst.consumeTarget()?.let { target ->
            showMode(target, announceHud = false)
        }
    }

    private val hideHud = Runnable {
        hud.animate()
            .alpha(0f)
            .setDuration(HUD_FADE_OUT_MILLIS)
            .withEndAction { hud.visibility = View.GONE }
            .start()
    }
    private val expireIdle = Runnable { dispatch(FrameIntent.IdleExpired) }
    private val refreshVisibleWeather = Runnable { refreshWeather() }
    private val preloadCalendar = Runnable {
        if (!FrameWebCompositionPolicy.forMode(state.mode).warmCalendarInBackground || !activityResumed) {
            return@Runnable
        }
        surfaceRouter?.calendarPreloadTarget()?.let { target ->
            webSurface?.preload(target.slot, target.url)
        }
    }

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
                    queueModeGesture(
                        direction = if (horizontalDistance < 0) {
                            ModeGestureDirection.NEXT
                        } else {
                            ModeGestureDirection.PREVIOUS
                        },
                        eventTimeMillis = end.eventTime,
                        repeatCount = 0,
                    )
                    return true
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configurationStore = FrameConfigurationStore(this)
        controlStore = FrameControlStore(this)
        val savedConfiguration = configurationStore.read()
        configuration = if (externalControlPolicy.acceptsProvisioning(savedConfiguration != null)) {
            persistProvisioning(intent) ?: savedConfiguration
        } else {
            savedConfiguration
        }
        buildUi()
        initializeWeatherRuntime()
        enterImmersiveMode()
        render(state, announce = false)
        handleOAuthCallback(intent)
        handleFrameCommand(intent)
        handlePendingControl()
        showStartupTransition()
        scheduleIdleReset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
        handleFrameCommand(intent)
        handlePendingControl()
        if (!bootRecoveryConfirmed && intent.action == FrameBootRecoveryScheduler.ACTION_RETRY) {
            webSurface?.retryDisplayedContentIfUnrendered()
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        enterImmersiveMode()
        render(state, announce = false)
        if (configuration == null) confirmBootRecoveryAfterNextDraw()
        if (pendingAuthorizationCode != null && !weatherAuthorizationInProgress) {
            startAuthorizationExchange()
        } else if (
            state.mode == FrameMode.WEATHER &&
            !weatherNeedsAuthentication &&
            !weatherAuthorizationInProgress &&
            !weatherRefreshInProgress
        ) {
            refreshWeather()
        }
    }

    override fun onPause() {
        activityResumed = false
        cancelPendingBootRecoveryConfirmation()
        volumeInput.reset()
        cancelModeGesture()
        handler.removeCallbacks(refreshVisibleWeather)
        handler.removeCallbacks(preloadCalendar)
        webSurface?.suspendAllContent()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) volumeInput.reset()
    }

    override fun onStop() {
        cancelHomeAssistantWork(stage = "activity_stop", clearPendingAuthorizationCode = false)
        super.onStop()
    }

    override fun onDestroy() {
        cancelHomeAssistantWork(stage = "activity_destroy", clearPendingAuthorizationCode = true)
        weatherWorkerExecutor.shutdownNow()
        weatherRequestExecutor.shutdownNow()
        authorizationExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        webSurface?.destroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        webSurface?.trimMemory(level)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleLenovoGestureKeyEvent(event)) return true
        if (handleTranslatedGestureKeyEvent(event)) return true
        if (handleContextualVolumeKeyEvent(event)) return true

        return when (val action = contextualInputMapper.map(state.mode, event.keyCode, event.isShiftPressed)) {
            is ContextualFrameAction.PhotoStep -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (webSurface?.movePhoto(action.forward) == true) {
                        showHud(if (action.forward) "Next photo" else "Previous photo")
                    }
                    scheduleIdleReset()
                }
                true
            }
            is ContextualFrameAction.WeatherPageStep -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    weatherContent.moveHourlyPage(action.forward)?.let(::showHud)
                    scheduleIdleReset()
                }
                true
            }
            is ContextualFrameAction.ForwardToWeb -> {
                val forwarded = if (action.keyCode != event.keyCode) {
                    KeyEvent(
                        event.downTime,
                        event.eventTime,
                        event.action,
                        action.keyCode,
                        event.repeatCount,
                        event.metaState,
                        event.deviceId,
                        event.scanCode,
                        event.flags,
                        event.source,
                    )
                } else {
                    event
                }
                val handled = forwardToWeb(forwarded)
                if (handled) scheduleIdleReset()
                handled || super.dispatchKeyEvent(event)
            }
            ContextualFrameAction.PrimaryAction -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    dispatch(FrameIntent.PrimaryAction)
                }
                true
            }
            null -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleLenovoGestureKeyEvent(event: KeyEvent): Boolean {
        val physicalIntent = inputMapper.mapLenovoGesture(
            keyCode = event.keyCode,
            scanCode = event.scanCode,
        )
        if (physicalIntent == null) return false
        if (event.action == KeyEvent.ACTION_UP) {
            queueModeGesture(
                direction = if (physicalIntent == FrameIntent.NextMode) {
                    ModeGestureDirection.NEXT
                } else {
                    ModeGestureDirection.PREVIOUS
                },
                eventTimeMillis = event.eventTime,
                repeatCount = event.repeatCount,
            )
        }
        return true
    }

    private fun handleTranslatedGestureKeyEvent(event: KeyEvent): Boolean {
        if (
            event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            return false
        }

        val physicalIntent = inputMapper.mapKeyDown(keyCode = event.keyCode, scanCode = event.scanCode)
        if (physicalIntent != FrameIntent.NextMode && physicalIntent != FrameIntent.PreviousMode) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            queueModeGesture(
                direction = if (physicalIntent == FrameIntent.NextMode) {
                    ModeGestureDirection.NEXT
                } else {
                    ModeGestureDirection.PREVIOUS
                },
                eventTimeMillis = event.eventTime,
                repeatCount = event.repeatCount,
            )
        }
        return true
    }

    private fun handleContextualVolumeKeyEvent(event: KeyEvent): Boolean {
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeButton.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeButton.DOWN
            else -> return false
        }

        val effect = when (event.action) {
            KeyEvent.ACTION_DOWN -> volumeInput.onDown(button, event.repeatCount)
            KeyEvent.ACTION_UP -> volumeInput.onUp(button)
            else -> null
        }
        when (effect) {
            is VolumeInputEffect.Step -> dispatchContextualTabClick(effect.forward, event.eventTime)
            VolumeInputEffect.RestoreAutoBrightness -> dispatch(FrameIntent.RestoreAutoBrightness)
            null -> Unit
        }
        return true
    }

    private fun dispatchContextualTabClick(forward: Boolean, eventTimeMillis: Long) {
        val keyCode = KeyEvent.KEYCODE_TAB
        val metaState = if (forward) 0 else KeyEvent.META_SHIFT_ON
        val down = KeyEvent(eventTimeMillis, eventTimeMillis, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
        val up = KeyEvent(eventTimeMillis, eventTimeMillis, KeyEvent.ACTION_UP, keyCode, 0, metaState)
        dispatchKeyEvent(down)
        dispatchKeyEvent(up)
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

        val physicalIntent = inputMapper.mapKeyDown(keyCode = keyCode, scanCode = event.scanCode)
        if (physicalIntent == FrameIntent.NextMode || physicalIntent == FrameIntent.PreviousMode) {
            queueModeGesture(
                direction = if (physicalIntent == FrameIntent.NextMode) {
                    ModeGestureDirection.NEXT
                } else {
                    ModeGestureDirection.PREVIOUS
                },
                eventTimeMillis = event.eventTime,
                repeatCount = event.repeatCount,
            )
            return true
        }

        val intent = physicalIntent ?: debugKeyboardIntent(keyCode)
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
                if (!starLongPressed) handleStarRelease(event)
                starLongPressed = false
                return true
            }
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
        weatherContent = WeatherContentView(this).apply { visibility = View.GONE }
        val fullScreenLayout = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val activeConfiguration = configuration
        if (activeConfiguration != null) {
            val runtime = (application as FrameOsApplication).geckoRuntime
            val warmHomeAssistantView = GeckoView(this).apply {
                setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
                setBackgroundColor(Color.BLACK)
                coverUntilFirstPaint(Color.BLACK)
                isFocusable = true
                isFocusableInTouchMode = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                visibility = View.GONE
            }
            val webListener = object : FrameWebSurface.Listener {
                override fun onPageLoading(label: String, loading: Boolean) {
                    runOnUiThread { updatePageLoading(label, loading) }
                }

                override fun onPageUnavailable(label: String) {
                    runOnUiThread {
                        updatePageLoading(label, false)
                        if (isWebLabelActive(label)) showWebRecovery("$label is temporarily unavailable")
                    }
                }

                override fun onPageRecovery(label: String, attempt: Int, retryInMillis: Long) {
                    runOnUiThread {
                        if (isWebLabelActive(label)) {
                            showWebRecovery("$label is unavailable · retry $attempt in ${retryInMillis / 1_000}s")
                        }
                    }
                }

                override fun onPageRecovered(label: String) {
                    runOnUiThread {
                        if (isWebLabelActive(label)) hideWebRecovery()
                    }
                }

                override fun onPageRenderInvalidated(label: String) {
                    runOnUiThread {
                        if (pendingBootRecoveryWebLabel == label) cancelPendingBootRecoveryConfirmation()
                    }
                }

                override fun onPageRendered(label: String) {
                    runOnUiThread {
                        if (
                            activityResumed &&
                            isWebLabelActive(label) &&
                            webSurface?.isDisplayingRenderedLabel(label) == true
                        ) {
                            confirmBootRecoveryAfterNextDraw(webLabel = label)
                        }
                    }
                }
            }
            surfaceRouter = FrameSurfaceRouter(activeConfiguration)
            webSurface = FrameWebSurface(
                context = this,
                runtime = runtime,
                photosUrl = activeConfiguration.photosUrl,
                homeAssistantUrl = activeConfiguration.homeAssistantUrl,
                homeAssistantFallbackUrl = activeConfiguration.homeAssistantFallbackUrl,
                birdsUrl = activeConfiguration.birdsUrl,
                warmHomeAssistantView = warmHomeAssistantView,
                listener = webListener,
                operationLogger = operationLogger,
            )
            root.addView(warmHomeAssistantView, fullScreenLayout)
            root.addView(webSurface, fullScreenLayout)
            root.addView(weatherContent, fullScreenLayout)
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
        webRecovery = buildWebRecoveryView()
        root.addView(webRecovery, fullScreenLayout)
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

    private fun buildWebRecoveryView(): LinearLayout {
        webRecoveryLabel = textView("", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(24), dp(36), dp(24))
            background = roundedBackground(0xE0181817.toInt(), strokeColor = 0x4DFFFFFF)
            visibility = View.GONE
            isFocusable = false
            isClickable = false
            addView(webRecoveryLabel)
            addView(
                textView("The last view will reconnect automatically. Use a mode gesture to try another view.", 16f, getColor(R.color.frame_ink_muted)).apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                },
            )
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
        cancelModeGesture()
        if (intent == FrameIntent.PrimaryAction && state.mode == FrameMode.WEATHER && weatherNeedsAuthentication) {
            beginWeatherAuthorization()
            scheduleIdleReset()
            return
        }
        val transition = reducer.reduce(state, intent)
        val modeChanged = transition.state.mode != state.mode
        state = transition.state
        applyBrightness(state.brightnessOverridePercent)
        render(state, announce = modeChanged)
        if (modeChanged && state.mode == FrameMode.WEATHER) refreshWeather()
        transition.effects.forEach(::applyEffect)
        scheduleIdleReset()
    }

    private fun queueModeGesture(
        direction: ModeGestureDirection,
        eventTimeMillis: Long,
        repeatCount: Int,
    ) {
        val update = modeGestureBurst.offer(
            displayedMode = state.mode,
            direction = direction,
            eventTimeMillis = eventTimeMillis,
            receivedAtMillis = SystemClock.uptimeMillis(),
            repeatCount = repeatCount,
            availableModes = FrameModeAvailability(configuration?.birdsUrl).cycleModes,
        ) ?: return

        handler.removeCallbacks(commitModeGesture)
        showHud(update.targetMode.label, update.targetMode)
        handler.postDelayed(commitModeGesture, update.commitDelayMillis)
        scheduleIdleReset()
    }

    private fun cancelModeGesture() {
        handler.removeCallbacks(commitModeGesture)
        modeGestureBurst.cancel()
    }

    private fun render(next: FrameState, announce: Boolean) {
        val router = surfaceRouter ?: return
        when (val target = router.target(next.mode)) {
            is FrameSurfaceTarget.Web -> when (target.slot) {
                FrameWebSlot.PHOTOS -> {
                    weatherContent.visibility = View.GONE
                    hideWebRecovery()
                    webSurface?.show(target.slot, target.url, takeFocus = false)
                    webSurface?.setContentActive(!next.photosPaused)
                    root.requestFocus()
                }
                FrameWebSlot.HOME_ASSISTANT, FrameWebSlot.CAMERAS -> {
                    weatherContent.visibility = View.GONE
                    hideWebRecovery()
                    webSurface?.show(target.slot, target.url, takeFocus = true)
                }
                FrameWebSlot.BIRDS -> {
                    weatherContent.visibility = View.GONE
                    hideWebRecovery()
                    webSurface?.show(target.slot, target.url, takeFocus = true)
                }
            }
            is FrameSurfaceTarget.Unavailable -> Unit
            FrameSurfaceTarget.NativeWeather -> {
                hideWebRecovery()
                webSurface?.hide()
                weatherContent.visibility = View.VISIBLE
                root.requestFocus()
                if (activityResumed) confirmBootRecoveryAfterNextDraw()
                if (announce) weatherContent.animate().alpha(0.82f).setDuration(70).withEndAction {
                    weatherContent.animate().alpha(1f).setDuration(180).start()
                }.start()
            }
        }
        scheduleCalendarPreload()
        scheduleWeatherRefresh()
    }

    private fun confirmBootRecoveryAfterNextDraw(webLabel: String? = null) {
        if (bootRecoveryConfirmed || !activityResumed) return
        if (pendingBootRecoveryDraw != null) {
            if (pendingBootRecoveryWebLabel == webLabel) return
            cancelPendingBootRecoveryConfirmation()
        }
        lateinit var drawListener: ViewTreeObserver.OnDrawListener
        drawListener = ViewTreeObserver.OnDrawListener {
            completeBootRecoveryAfterDraw(drawListener)
        }
        pendingBootRecoveryDraw = drawListener
        pendingBootRecoveryObserver = root.viewTreeObserver
        pendingBootRecoveryWebLabel = webLabel
        root.viewTreeObserver.addOnDrawListener(drawListener)
        root.invalidate()
    }

    private fun completeBootRecoveryAfterDraw(drawListener: ViewTreeObserver.OnDrawListener) {
        root.post {
            if (pendingBootRecoveryDraw !== drawListener) return@post
            val pendingWebLabel = pendingBootRecoveryWebLabel
            cancelPendingBootRecoveryConfirmation()
            if (!activityResumed || bootRecoveryConfirmed) return@post
            if (
                pendingWebLabel != null &&
                webSurface?.isDisplayingRenderedLabel(pendingWebLabel) != true
            ) {
                return@post
            }
            FrameBootRecoveryScheduler.cancelRetries(this)
            bootRecoveryConfirmed = true
        }
    }

    private fun cancelPendingBootRecoveryConfirmation() {
        val observer = pendingBootRecoveryObserver
        val listener = pendingBootRecoveryDraw
        if (observer?.isAlive == true && listener != null) observer.removeOnDrawListener(listener)
        pendingBootRecoveryObserver = null
        pendingBootRecoveryDraw = null
        pendingBootRecoveryWebLabel = null
    }

    private fun scheduleCalendarPreload() {
        handler.removeCallbacks(preloadCalendar)
        if (FrameWebCompositionPolicy.forMode(state.mode).warmCalendarInBackground && activityResumed) {
            handler.postDelayed(preloadCalendar, CALENDAR_PRELOAD_DELAY_MILLIS)
        }
    }

    private fun updatePageLoading(label: String, loading: Boolean) {
        slowPageLoads.remove(label)?.let(handler::removeCallbacks)
        if (!loading) return
        val delayedHud = Runnable {
            slowPageLoads.remove(label)
            if (isWebLabelActive(label)) showHud("Loading $label…")
        }
        slowPageLoads[label] = delayedHud
        handler.postDelayed(delayedHud, SLOW_PAGE_LOAD_MILLIS)
    }

    private fun isWebLabelActive(label: String): Boolean = when (label) {
        FrameMode.PHOTOS.label -> state.mode == FrameMode.PHOTOS
        "Home Assistant" -> state.mode in HOME_ASSISTANT_MODES
        FrameMode.CAMERAS.label -> state.mode == FrameMode.CAMERAS
        FrameMode.BIRDS.label -> state.mode == FrameMode.BIRDS
        else -> false
    }

    private fun handleFrameCommand(intent: Intent) {
        if (!externalControlPolicy.acceptsCommands()) {
            intent.removeExtra(FrameControlContract.EXTRA_COMMAND)
            intent.removeExtra(FrameControlContract.EXTRA_MODE)
            return
        }
        val command = intent.getStringExtra(FrameControlContract.EXTRA_COMMAND)?.lowercase(Locale.ROOT)
        val requestedMode = intent.getStringExtra(FrameControlContract.EXTRA_MODE)?.uppercase(Locale.ROOT)
            ?.let { name -> FrameMode.entries.firstOrNull { it.name == name } }
        intent.removeExtra(FrameControlContract.EXTRA_COMMAND)
        intent.removeExtra(FrameControlContract.EXTRA_MODE)

        when {
            requestedMode != null -> showMode(requestedMode)
            command == "next" -> dispatch(FrameIntent.NextMode)
            command == "previous" || command == "prev" -> dispatch(FrameIntent.PreviousMode)
            command == "home" -> dispatch(FrameIntent.GoHome)
        }
    }

    private fun handlePendingControl() {
        when (val command = controlStore.consume()) {
            FrameControlCommand.Next -> dispatch(FrameIntent.NextMode)
            FrameControlCommand.Previous -> dispatch(FrameIntent.PreviousMode)
            FrameControlCommand.Home -> dispatch(FrameIntent.GoHome)
            is FrameControlCommand.Show -> showMode(command.mode)
            null -> Unit
        }
    }

    private fun showMode(mode: FrameMode, announceHud: Boolean = true) {
        cancelModeGesture()
        val resolvedMode = FrameModeAvailability(configuration?.birdsUrl).resolve(mode)
        val unavailableBirds = mode == FrameMode.BIRDS && resolvedMode != mode
        val changed = state.mode != resolvedMode
        state = state.copy(
            mode = resolvedMode,
            photosPaused = if (resolvedMode == FrameMode.PHOTOS) false else state.photosPaused,
        )
        render(state, announce = changed)
        if (resolvedMode == FrameMode.WEATHER) refreshWeather()
        if (unavailableBirds) {
            showHud("Birds is not configured")
        } else if (changed && announceHud) {
            showHud()
        }
        scheduleIdleReset()
    }

    private fun persistProvisioning(intent: Intent): FrameConfiguration? {
        if (
            !intent.hasExtra(FrameControlContract.EXTRA_PHOTOS_URL) &&
            !intent.hasExtra(FrameControlContract.EXTRA_HOME_ASSISTANT_URL)
        ) return null
        val provisioned = FrameConfiguration.from(
            photosUrl = intent.getStringExtra(FrameControlContract.EXTRA_PHOTOS_URL).orEmpty(),
            homeAssistantUrl = intent.getStringExtra(FrameControlContract.EXTRA_HOME_ASSISTANT_URL).orEmpty(),
            weatherEntityId = intent
                .getStringExtra(FrameControlContract.EXTRA_WEATHER_ENTITY_ID)
                .orEmpty()
                .ifBlank { DEFAULT_WEATHER_ENTITY_ID },
            homeAssistantFallbackUrl = intent
                .getStringExtra(FrameControlContract.EXTRA_HOME_ASSISTANT_FALLBACK_URL),
            birdsUrl = intent.getStringExtra(FrameControlContract.EXTRA_BIRDS_URL),
        ) ?: return null
        configurationStore.write(provisioned)
        SharedPreferencesWeatherCache(this).clear()
        return provisioned
    }

    private fun initializeWeatherRuntime() {
        val activeConfiguration = configuration ?: return
        try {
            val callbackPageUrl = callbackPageUrl(activeConfiguration.homeAssistantUrl)
            val transport = UrlConnectionHomeAssistantTransport()
            val sessionStore = AndroidKeystoreOAuthSessionStore(this, callbackPageUrl)
            val endpoint = HomeAssistantOAuthEndpoint.fromDisplayUrl(activeConfiguration.homeAssistantUrl)
            val pendingStateStore = SharedPreferencesPendingOAuthStateStore(this)
            val client = HomeAssistantOAuthClient(
                endpoint = endpoint,
                callbackPageUrl = callbackPageUrl,
                transport = transport,
                store = sessionStore,
                clock = System::currentTimeMillis,
                operationLogger = operationLogger,
            )
            val weatherEndpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl(activeConfiguration.homeAssistantUrl)
            val cache = SharedPreferencesWeatherCache(this)
            homeAssistantTransport = transport
            oauthEndpoint = endpoint
            oauthCallbackPageUrl = callbackPageUrl
            pendingOAuthStateStore = pendingStateStore
            oauthClient = client
            callbackVerifier = OAuthCallbackVerifier(pendingStateStore)
            weatherCoordinator = WeatherCoordinator(
                accessTokenProvider = client,
                repository = WeatherRepository(
                    remote = HomeAssistantWeatherRemote(
                        endpoint = weatherEndpoint,
                        fallbackEndpoint = activeConfiguration.homeAssistantFallbackUrl?.let(
                            HomeAssistantWeatherEndpoint::fromDisplayUrl,
                        ),
                        transport = transport,
                        parser = HomeAssistantWeatherParser(),
                        requestExecutor = weatherRequestExecutor,
                        operationLogger = operationLogger,
                    ),
                    cache = cache,
                    clock = System::currentTimeMillis,
                    freshForMillis = WEATHER_CACHE_FRESH_MILLIS,
                    homeAssistantOrigin = weatherEndpoint.canonicalOrigin,
                    authEpochProvider = client::authEpoch,
                ),
                presenter = WeatherPresenter(ZoneId.systemDefault(), Locale.getDefault()),
                entityId = activeConfiguration.weatherEntityId,
            )
            weatherCache = cache
            weatherNeedsAuthentication = false
            pendingAuthorizationCode = null
            pendingAuthorizationPreviousAuthEpoch = null
            weatherContent.presentation = WeatherPresentation(
                emptyMessage = "Loading the latest forecast",
            )
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            homeAssistantTransport = null
            weatherNeedsAuthentication = false
            weatherContent.presentation = WeatherPresentation(
                emptyMessage = "Weather needs a secure Home Assistant URL",
            )
        }
    }

    private fun refreshWeather() {
        val coordinator = weatherCoordinator ?: return
        if (weatherAuthorizationInProgress || weatherRefreshInProgress || pendingAuthorizationCode != null) return
        weatherRefreshInProgress = true
        weatherRefreshStartedAtUptimeMillis = SystemClock.uptimeMillis()
        handler.removeCallbacks(refreshVisibleWeather)
        val generation = ++weatherRequestGeneration
        weatherRefreshFuture = weatherWorkerExecutor.submit {
            coordinator.cachedPresentation()?.let { cached ->
                handler.post {
                    if (generation != weatherRequestGeneration || isFinishing || isDestroyed) return@post
                    weatherContent.presentation = cached
                }
            }
            val presentation = try {
                coordinator.refresh()
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                WeatherPresentation(emptyMessage = "Weather is temporarily unavailable")
            }
            handler.post {
                if (generation != weatherRequestGeneration || isFinishing || isDestroyed) return@post
                weatherRefreshFuture = null
                weatherRefreshStartedAtUptimeMillis = 0L
                weatherRefreshInProgress = false
                weatherContent.presentation = presentation
                weatherNeedsAuthentication = presentation.authenticationRequired ||
                    presentation.emptyMessage == "Weather needs Home Assistant sign-in"
                scheduleWeatherRefresh()
            }
        }
    }

    private fun scheduleWeatherRefresh() {
        handler.removeCallbacks(refreshVisibleWeather)
        WeatherRefreshPolicy.nextDelayMillis(
            weatherVisible = state.mode == FrameMode.WEATHER,
            activityResumed = activityResumed,
            authenticationRequired = weatherNeedsAuthentication,
            authorizationInProgress = weatherAuthorizationInProgress || weatherRefreshInProgress || pendingAuthorizationCode != null,
        )?.let { delay -> handler.postDelayed(refreshVisibleWeather, delay) }
    }

    private fun beginWeatherAuthorization() {
        val endpoint = oauthEndpoint ?: return
        val callbackPageUrl = oauthCallbackPageUrl ?: return
        val pendingStateStore = pendingOAuthStateStore ?: return
        val randomBytes = ByteArray(OAUTH_STATE_BYTES).also(secureRandom::nextBytes)
        val state = OAuthState.fromCryptographicBytes(randomBytes)
        pendingStateStore.write(state)
        val authorization = endpoint.authorizationRequest(callbackPageUrl, state)
        weatherContent.presentation = WeatherPresentation(emptyMessage = "Continue sign-in in the browser")
        weatherNeedsAuthentication = false
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorization.url)))
        } catch (error: ActivityNotFoundException) {
            pendingStateStore.consume()
            weatherNeedsAuthentication = true
            weatherContent.presentation = WeatherPresentation(emptyMessage = "Weather needs Home Assistant sign-in")
        }
    }

    private fun handleOAuthCallback(intent: Intent) {
        val callbackUrl = intent.dataString ?: return
        val verifier = callbackVerifier ?: return
        val code = verifier.verify(callbackUrl) ?: run {
            setIntent(Intent(this, MainActivity::class.java))
            pendingAuthorizationCode = null
            pendingAuthorizationPreviousAuthEpoch = null
            weatherAuthorizationInProgress = false
            weatherNeedsAuthentication = true
            weatherContent.presentation = WeatherPresentation(emptyMessage = "Weather needs Home Assistant sign-in")
            showHud("Sign-in link expired")
            return
        }
        setIntent(Intent(this, MainActivity::class.java))
        state = state.copy(mode = FrameMode.WEATHER)
        render(state, announce = false)
        weatherContent.presentation = WeatherPresentation(emptyMessage = "Connecting to Home Assistant")
        weatherNeedsAuthentication = false
        pendingAuthorizationPreviousAuthEpoch = oauthClient?.authEpoch()
        pendingAuthorizationCode = code.code
        startAuthorizationExchange()
    }

    private fun startAuthorizationExchange() {
        val client = oauthClient ?: return
        val code = pendingAuthorizationCode ?: return
        if (weatherAuthorizationInProgress) return
        val previousAuthEpoch = pendingAuthorizationPreviousAuthEpoch
        if (
            previousAuthEpoch != null &&
            client.authEpoch() != previousAuthEpoch &&
            client.validAccessToken() != null
        ) {
            pendingAuthorizationCode = null
            pendingAuthorizationPreviousAuthEpoch = null
            weatherCache?.clear()
            refreshWeather()
            return
        }
        weatherAuthorizationInProgress = true
        authorizationStartedAtUptimeMillis = SystemClock.uptimeMillis()
        handler.removeCallbacks(refreshVisibleWeather)
        val generation = ++weatherRequestGeneration
        authorizationFuture = authorizationExecutor.submit {
            val success = client.exchangeAuthorizationCode(
                code,
                WeatherRequestDeadline(timeoutMillis = OAUTH_EXCHANGE_TIMEOUT_MILLIS),
            )
            handler.post {
                if (generation != weatherRequestGeneration || isFinishing || isDestroyed) return@post
                authorizationFuture = null
                authorizationStartedAtUptimeMillis = 0L
                weatherAuthorizationInProgress = false
                if (success) {
                    pendingAuthorizationCode = null
                    pendingAuthorizationPreviousAuthEpoch = null
                    weatherCache?.clear()
                    refreshWeather()
                } else {
                    pendingAuthorizationCode = null
                    pendingAuthorizationPreviousAuthEpoch = null
                    weatherNeedsAuthentication = true
                    weatherContent.presentation = WeatherPresentation(emptyMessage = "Weather needs Home Assistant sign-in")
                    showHud("Home Assistant sign-in failed")
                    scheduleWeatherRefresh()
                }
            }
        }
    }

    private fun cancelHomeAssistantWork(stage: String, clearPendingAuthorizationCode: Boolean) {
        weatherRequestGeneration += 1
        homeAssistantTransport?.cancelInFlight()
        weatherCoordinator?.cancel()
        weatherRefreshFuture?.cancel(true)
        if (weatherRefreshInProgress) logCancellation("weather", stage, weatherRefreshStartedAtUptimeMillis)
        weatherRefreshFuture = null
        weatherRefreshStartedAtUptimeMillis = 0L
        weatherRefreshInProgress = false
        authorizationFuture?.cancel(true)
        if (weatherAuthorizationInProgress) logCancellation("oauth", stage, authorizationStartedAtUptimeMillis)
        authorizationFuture = null
        authorizationStartedAtUptimeMillis = 0L
        weatherAuthorizationInProgress = false
        if (clearPendingAuthorizationCode) {
            pendingAuthorizationCode = null
            pendingAuthorizationPreviousAuthEpoch = null
        }
    }

    private fun logCancellation(route: String, stage: String, startedAtUptimeMillis: Long) {
        operationLogger.log(
            FrameOperationLogEntry(
                route = route,
                stage = stage,
                outcome = "cancelled",
                elapsedMillis = if (startedAtUptimeMillis == 0L) 0L else {
                    (SystemClock.uptimeMillis() - startedAtUptimeMillis).coerceAtLeast(0L)
                },
            ),
        )
    }

    private fun callbackPageUrl(homeAssistantUrl: String): String {
        val configured = URI(homeAssistantUrl)
        return URI(
            configured.scheme,
            null,
            configured.host,
            configured.port,
            HomeAssistantOAuthEndpoint.CALLBACK_PATH,
            null,
            null,
        ).toASCIIString()
    }

    private fun applyEffect(effect: FrameEffect) {
        when (effect) {
            is FrameEffect.AnnounceMode -> showHud()
            is FrameEffect.AnnounceMessage -> showHud(effect.message)
        }
    }

    private fun handleStarRelease(source: KeyEvent) {
        when (val action = contextualInputMapper.mapStarRelease(state.mode)) {
            is ContextualFrameAction.ForwardToWeb -> {
                val eventTime = source.eventTime
                val down = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, action.keyCode, 0)
                val up = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, action.keyCode, 0)
                forwardToWeb(down)
                forwardToWeb(up)
                scheduleIdleReset()
            }
            ContextualFrameAction.PrimaryAction -> dispatch(FrameIntent.PrimaryAction)
            else -> Unit
        }
    }

    private fun forwardToWeb(event: KeyEvent): Boolean {
        val surface = webSurface ?: return false
        return surface.forwardKey(event)
    }

    private fun showHud(message: String = state.mode.label) {
        showHud(message, state.mode)
    }

    private fun showHud(message: String, positionMode: FrameMode) {
        val modes = FrameModeAvailability(configuration?.birdsUrl).cycleModes
        val position = modes.indexOf(positionMode).coerceAtLeast(0)
        handler.removeCallbacks(hideHud)
        hud.animate().cancel()
        hudLabel.text = message
        hudPosition.text = modes.indices.joinToString("  ") { index -> if (index == position) "●" else "○" }
        hud.contentDescription = "$message. ${position + 1} of ${modes.size}."
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
        handler.postDelayed(hideHud, HUD_VISIBLE_MILLIS)
    }

    private fun showWebRecovery(message: String) {
        webRecoveryLabel.text = message
        webRecovery.contentDescription = message
        webRecovery.visibility = View.VISIBLE
        webRecovery.alpha = 1f
        webRecovery.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }

    private fun hideWebRecovery() {
        if (::webRecovery.isInitialized) webRecovery.visibility = View.GONE
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
        const val CALENDAR_PRELOAD_DELAY_MILLIS = 2_000L
        const val SLOW_PAGE_LOAD_MILLIS = 450L
        const val HUD_FADE_IN_MILLIS = 160L
        const val HUD_FADE_OUT_MILLIS = 220L
        const val HUD_VISIBLE_MILLIS = 1_500L
        const val IDLE_TIMEOUT_MILLIS = 15 * 60 * 1_000L
        const val WEATHER_CACHE_FRESH_MILLIS = 5 * 60 * 1_000L
        const val OAUTH_EXCHANGE_TIMEOUT_MILLIS = 10_000L
        const val OAUTH_STATE_BYTES = 32
        const val DEFAULT_WEATHER_ENTITY_ID = "weather.home"
        val HOME_ASSISTANT_MODES = setOf(FrameMode.HOME, FrameMode.CALENDAR)
    }
}
