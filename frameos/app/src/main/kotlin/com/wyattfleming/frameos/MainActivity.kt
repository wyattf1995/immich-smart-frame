package com.wyattfleming.frameos

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Bitmap
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.wyattfleming.frameos.navigation.ContextualFrameAction
import com.wyattfleming.frameos.navigation.ContextualInputMapper
import com.wyattfleming.frameos.navigation.FrameControlHints
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
import com.wyattfleming.frameos.config.FrameExperienceSettings
import com.wyattfleming.frameos.config.FrameExperienceStore
import com.wyattfleming.frameos.config.QuietHoursBrightnessPolicy
import com.wyattfleming.frameos.control.FrameControlCommand
import com.wyattfleming.frameos.control.FrameControlContract
import com.wyattfleming.frameos.control.FrameControlStore
import com.wyattfleming.frameos.control.FrameCompanionClient
import com.wyattfleming.frameos.control.FrameCompanionCredentialsStore
import com.wyattfleming.frameos.control.FrameRemoteStatus
import com.wyattfleming.frameos.control.FrameCompanionCommandDecoder
import com.wyattfleming.frameos.control.FrameRemoteCommand
import com.wyattfleming.frameos.control.FrameCompanionCommandStore
import com.wyattfleming.frameos.control.FrameCompanionSettingsDecoder
import com.wyattfleming.frameos.control.FrameCompanionResponseDecoder
import com.wyattfleming.frameos.control.FrameCompanionEventDecoder
import com.wyattfleming.frameos.control.FrameCompanionEventStore
import com.wyattfleming.frameos.control.FramePhotosProfileUrl
import com.wyattfleming.frameos.control.FrameRemoteSettings
import com.wyattfleming.frameos.security.FrameExternalControlPolicy
import com.wyattfleming.frameos.diagnostics.FrameDiagnosticStore
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
import java.time.LocalTime
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

class MainActivity : Activity() {
    private val reducer = FrameReducer { availableModes() }
    private val inputMapper = PhysicalInputMapper()
    private val contextualInputMapper = ContextualInputMapper()
    private val handler = Handler(Looper.getMainLooper())
    private val modeGestureBurst = ModeGestureBurst()
    private val weatherWorkerExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val companionWorkerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
    private lateinit var experienceStore: FrameExperienceStore
    private lateinit var diagnosticStore: FrameDiagnosticStore
    private var experienceSettings = FrameExperienceSettings()
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
    private lateinit var photoReserve: com.wyattfleming.frameos.photos.FrameOfflineReserve
    private lateinit var photoBridge: com.wyattfleming.frameos.photos.FramePhotoBridge
    private var currentPhotoAssetId: String? = null
    private var lastPhotoAt: Long? = null
    private lateinit var offlinePhoto: ImageView
    private val photoFallbackPolicy = com.wyattfleming.frameos.photos.FramePhotoFallbackPolicy()
    private var fallbackIndex = 0
    private var offlineBitmap: Bitmap? = null
    @Volatile private var photoDisplayGeneration = 0L
    @Volatile private var photoProbeGeneration = 0L
    private var photoProbeInFlight = false
    private var healthyPhotoProbeAt: Long? = null
    @Volatile private var activePhotoConnection: java.net.HttpURLConnection? = null
    private var photoProbeFuture: Future<*>? = null
    private var photoDecodeFuture: Future<*>? = null
    private var manualPhotoStepPending = false
    private val finishManualPhotoStep = Runnable {
        manualPhotoStepPending = false
        photoBridge.setCaptureEnabled(activityResumed && state.mode == FrameMode.PHOTOS, state.photosPaused)
    }
    private var photosVisibleSince = 0L
    private var photosWereActive = false
    private val photoWorkerExecutor = Executors.newSingleThreadExecutor()
    private val slowPageLoads = mutableMapOf<String, Runnable>()
    private var bootRecoveryConfirmed = false
    private var companionPollInFlight = false
    private var companionPollGeneration = 0L
    private val pollCompanion = Runnable { pollCompanion() }
    private val refreshQuietHours = Runnable {
        if (activityResumed) applyBrightness(state.brightnessOverridePercent)
    }
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
    private val resumePhotosAfterHold = Runnable {
        if (state.photosPaused) {
            state = state.copy(photosPaused = false)
            if (activityResumed && state.mode == FrameMode.PHOTOS) render(state, announce = false)
        }
    }
    private val refreshPhotoFreshness = Runnable { checkPhotoFreshness() }
    private val rotateOfflinePhoto = Runnable { showOfflinePhoto(step = 1) }
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
        experienceStore = FrameExperienceStore(this)
        diagnosticStore = FrameDiagnosticStore(this)
        photoReserve = com.wyattfleming.frameos.photos.FrameOfflineReserve(java.io.File(filesDir, "frame-offline-reserve"))
        photoBridge = com.wyattfleming.frameos.photos.FramePhotoBridge(photoReserve) { photo ->
            handler.post {
                if (!photoBridge.isCurrentObservation(photo)) return@post
                if (!activityResumed || state.mode != FrameMode.PHOTOS || (state.photosPaused && !manualPhotoStepPending)) return@post
                currentPhotoAssetId = photo.assetId
                lastPhotoAt = photo.capturedAt
                if (photoFallbackPolicy.canDismiss(photo.capturedAt, healthyPhotoProbeAt)) {
                    photoFallbackPolicy.recordFreshPhoto()
                    hideOfflinePhoto()
                }
                if (manualPhotoStepPending) {
                    handler.removeCallbacks(finishManualPhotoStep)
                    finishManualPhotoStep.run()
                }
            }
        }
        experienceSettings = experienceStore.read()
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
        scheduleCompanionPoll()
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
        refreshLocalProvisioning()
        applyBrightness(state.brightnessOverridePercent)
        enterImmersiveMode()
        render(state, announce = false)
        if (configuration == null) confirmBootRecoveryAfterNextDraw()
        scheduleCompanionPoll()
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
        invalidateCompanionPoll()
        cancelPendingBootRecoveryConfirmation()
        volumeInput.reset()
        cancelModeGesture()
        handler.removeCallbacks(refreshVisibleWeather)
        handler.removeCallbacks(preloadCalendar)
        handler.removeCallbacks(pollCompanion)
        handler.removeCallbacks(refreshQuietHours)
        cancelPhotoWork()
        hideOfflinePhoto()
        photoBridge.setCaptureEnabled(false, true)
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
        invalidateCompanionPoll()
        cancelHomeAssistantWork(stage = "activity_destroy", clearPendingAuthorizationCode = true)
        weatherWorkerExecutor.shutdownNow()
        companionWorkerExecutor.shutdownNow()
        cancelPhotoWork()
        hideOfflinePhoto()
        photoWorkerExecutor.shutdownNow()
        photoBridge.close()
        weatherRequestExecutor.shutdownNow()
        authorizationExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        webSurface?.destroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        webSurface?.trimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) photoReserve.clearForLowMemory()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleLenovoGestureKeyEvent(event)) return true
        if (handleTranslatedGestureKeyEvent(event)) return true
        if (handleContextualVolumeKeyEvent(event)) return true

        return when (val action = contextualInputMapper.map(state.mode, event.keyCode, event.isShiftPressed)) {
            is ContextualFrameAction.PhotoStep -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (movePhoto(action.forward)) {
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
        offlinePhoto = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            contentDescription = "Recent photo while Photos reconnects"
        }
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
                        diagnosticStore.recordRecovery("$label unavailable")
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
                        diagnosticStore.recordPaint(System.currentTimeMillis())
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
                photoBridge = photoBridge,
                photosProfile = FramePhotosProfileUrl.profile(activeConfiguration.photosUrl) ?: "balanced",
                deactivateHiddenHomeAssistant = experienceSettings.deactivateHiddenHomeAssistant,
            )
            root.addView(warmHomeAssistantView, fullScreenLayout)
            root.addView(webSurface, fullScreenLayout)
            root.addView(weatherContent, fullScreenLayout)
            root.addView(offlinePhoto, fullScreenLayout)
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
        if (intent == FrameIntent.PrimaryAction && state.mode == FrameMode.PHOTOS) {
            handler.removeCallbacks(resumePhotosAfterHold)
        }
        if (intent == FrameIntent.PrimaryAction && state.mode == FrameMode.WEATHER && weatherNeedsAuthentication) {
            beginWeatherAuthorization()
            scheduleIdleReset()
            return
        }
        val transition = reducer.reduce(state, intent)
        val modeChanged = transition.state.mode != state.mode
        state = transition.state.let { next -> next.copy(mode = availability().resolve(next.mode)) }
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
            availableModes = availableModes(),
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
        if (::diagnosticStore.isInitialized) diagnosticStore.recordMode(next.mode)
        if (next.mode != FrameMode.PHOTOS || next.photosPaused || !activityResumed) {
            cancelPhotoWork()
            photoBridge.setCaptureEnabled(false, true)
        }
        val router = surfaceRouter ?: return
        when (val target = router.target(next.mode)) {
            is FrameSurfaceTarget.Web -> when (target.slot) {
                FrameWebSlot.PHOTOS -> {
                    weatherContent.visibility = View.GONE
                    hideWebRecovery()
                    webSurface?.show(target.slot, target.url, takeFocus = false)
                    webSurface?.setContentActive(activityResumed)
                    webSurface?.setPhotosPaused(next.photosPaused)
                    root.requestFocus()
                    if (photoFallbackPolicy.isShowing) showOfflinePhoto(manual = true)
                    schedulePhotoFreshness()
                }
                FrameWebSlot.HOME_ASSISTANT, FrameWebSlot.CAMERAS -> {
                    hideOfflinePhoto()
                    weatherContent.visibility = View.GONE
                    hideWebRecovery()
                    webSurface?.show(target.slot, target.url, takeFocus = true)
                }
                FrameWebSlot.BIRDS -> {
                    hideOfflinePhoto()
                    weatherContent.visibility = View.GONE
                    hideWebRecovery()
                    webSurface?.show(target.slot, target.url, takeFocus = true)
                }
            }
            is FrameSurfaceTarget.Unavailable -> Unit
            FrameSurfaceTarget.NativeWeather -> {
                hideOfflinePhoto()
                hideWebRecovery()
                webSurface?.hide()
                weatherContent.visibility = View.VISIBLE
                diagnosticStore.recordPaint(System.currentTimeMillis())
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
        val resolvedMode = availability().resolve(mode)
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

    private fun availability(): FrameModeAvailability = FrameModeAvailability(
        birdsUrl = configuration?.birdsUrl,
        requestedModes = experienceSettings.orderedEnabledModes,
    )

    private fun availableModes(): List<FrameMode> = availability().cycleModes

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
                presentation.freshDataEpochMillis?.let(diagnosticStore::recordWeatherSuccess)
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

    private fun scheduleCompanionPoll(delayMillis: Long = 5_000L) {
        handler.removeCallbacks(pollCompanion)
        if (activityResumed && !isFinishing && !isDestroyed && !companionPollInFlight) {
            handler.postDelayed(pollCompanion, delayMillis)
        }
    }

    private fun pollCompanion() {
        if (!activityResumed || companionPollInFlight) return
        val credentials = FrameCompanionCredentialsStore(this).read() ?: return
        companionPollInFlight = true
        val generation = companionPollGeneration
        val snapshot = diagnosticStore.snapshot()
        val reserveStatus = photoReserve.status()
        val status = FrameRemoteStatus(
            mode = state.mode,
            photosPaused = state.photosPaused,
            lastPaintAt = snapshot.lastPaintEpochMillis.takeIf { it > 0 },
            lastWeatherAt = snapshot.lastWeatherSuccessEpochMillis.takeIf { it > 0 },
            recoveryCount = snapshot.recoveryCount,
            lastError = snapshot.lastError,
            offline = ::offlinePhoto.isInitialized && offlinePhoto.visibility == View.VISIBLE,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            currentAssetId = currentPhotoAssetId,
            lastPhotoAt = lastPhotoAt,
            profile = configuration?.photosUrl?.let(FramePhotosProfileUrl::profile),
            offlineAssets = reserveStatus.offlineAssets,
            offlineBytes = reserveStatus.offlineBytes,
        )
        companionWorkerExecutor.submit {
            val pendingAcks = FrameCompanionCommandStore(this).consumeAcks()
            val result = FrameCompanionClient(credentials.endpoint, credentials.token).poll(
                credentials.deviceId,
                status,
                pendingAcks,
            )
            handler.post {
                companionPollInFlight = false
                if (
                    generation != companionPollGeneration ||
                    !activityResumed ||
                    isFinishing ||
                    isDestroyed ||
                    FrameCompanionCredentialsStore(this).read() != credentials
                ) {
                    scheduleCompanionPoll()
                    return@post
                }
                if (
                    result is com.wyattfleming.frameos.control.FrameCompanionPollResult.Success &&
                    FrameCompanionResponseDecoder.isForDevice(result.body, credentials.deviceId)
                ) {
                    FrameCompanionCommandStore(this).clearAcks(pendingAcks)
                    FrameCompanionSettingsDecoder.decode(result.body)?.let(::applyCompanionSettings)
                    applyHiddenPhotoSnapshot(result.body)
                    maybeShowCompanionEvent(result.body)
                    FrameCompanionCommandDecoder.decode(result.body, System.currentTimeMillis())?.let { command ->
                        val store = FrameCompanionCommandStore(this)
                        if (store.claim(command.id)) {
                            store.ack(command.id, applyCompanionCommand(command))
                        }
                    }
                }
                scheduleCompanionPoll(if (result is com.wyattfleming.frameos.control.FrameCompanionPollResult.Success) 5_000L else 10_000L)
            }
        }
    }

    private fun invalidateCompanionPoll() {
        companionPollGeneration += 1
    }

    private fun refreshLocalProvisioning() {
        val persisted = experienceStore.read()
        if (persisted.settingsRevision < experienceSettings.settingsRevision) experienceSettings = persisted
        val updated = configurationStore.read() ?: return
        if (updated != configuration) {
            configuration = updated
            invalidateCompanionPoll()
            rebuildExperienceUi()
        }
    }

    private fun applyHiddenPhotoSnapshot(payload: String) {
        val ids = runCatching {
            val values = org.json.JSONObject(payload).optJSONArray("hiddenAssets") ?: return
            require(values.length() <= 1_000)
            buildSet {
                repeat(values.length()) { index ->
                    val id = values.getString(index)
                    require(Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$").matches(id))
                    add(id.lowercase(Locale.ROOT))
                }
            }
        }.getOrNull() ?: return
        if (!photoReserve.applyHiddenAssets(ids)) return
        if (currentPhotoAssetId?.lowercase(Locale.ROOT) in ids) {
            currentPhotoAssetId = null
            if (state.mode == FrameMode.PHOTOS) {
                if (photoFallbackPolicy.isShowing) showOfflinePhoto(manual = true) else movePhoto(true)
            }
        }
    }

    private fun maybeShowCompanionEvent(payload: String) {
        if (!experienceSettings.eventOverlaysEnabled || state.mode != FrameMode.PHOTOS || state.photosPaused) return
        if (experienceSettings.quietHours?.isActiveAt(LocalTime.now()) == true) return
        val now = System.currentTimeMillis()
        val event = FrameCompanionEventDecoder.decode(payload, now) ?: return
        if (!FrameCompanionEventStore(this).claim(event.id, now)) return
        showHud(event.text)
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, EVENT_OVERLAY_VISIBLE_MILLIS)
    }

    private fun applyCompanionCommand(command: FrameRemoteCommand): String = when (command) {
        is FrameRemoteCommand.ShowMode -> if (availability().resolve(command.mode) == command.mode) {
            showMode(command.mode); "applied"
        } else "rejected"
        is FrameRemoteCommand.PhotoStep -> if (state.mode == FrameMode.PHOTOS) {
            val moved = movePhoto(command.forward)
            if (moved) "dispatched" else "rejected"
        } else "rejected"
        is FrameRemoteCommand.PhotoPause -> if (state.mode == FrameMode.PHOTOS) {
            handler.removeCallbacks(resumePhotosAfterHold)
            if (state.photosPaused != command.paused) dispatch(FrameIntent.PrimaryAction)
            "applied"
        } else "rejected"
        is FrameRemoteCommand.PhotoHold -> if (state.mode == FrameMode.PHOTOS) {
            if (!state.photosPaused) dispatch(FrameIntent.PrimaryAction)
            handler.removeCallbacks(resumePhotosAfterHold)
            handler.postDelayed(resumePhotosAfterHold, command.durationSeconds * 1_000L)
            "applied"
        } else "rejected"
        is FrameRemoteCommand.SetProfile -> if (setPhotosProfile(command.profile)) "applied" else "failed"
    }

    private fun applyCompanionSettings(settings: FrameRemoteSettings) {
        if (settings.revision <= experienceSettings.settingsRevision) return
        val updated = runCatching {
            experienceSettings.copy(
                orderedEnabledModes = settings.modeOrder,
                quietHours = settings.quietHours,
                deactivateHiddenHomeAssistant = settings.hiddenHomeSuspend,
                idleReturnSeconds = settings.idleReturnSeconds,
                eventOverlaysEnabled = settings.eventOverlaysEnabled,
                settingsRevision = settings.revision,
            )
        }.getOrNull() ?: return
        experienceSettings = updated
        experienceStore.write(updated)
        updatePhotosProfile(settings.profile)
        rebuildExperienceUi()
    }

    private fun setPhotosProfile(profile: String): Boolean {
        if (!updatePhotosProfile(profile)) return false
        refreshPhotos()
        return true
    }

    private fun updatePhotosProfile(profile: String): Boolean {
        val current = configuration ?: return false
        val updatedPhotosUrl = FramePhotosProfileUrl.withProfile(current.photosUrl, profile) ?: return false
        val updated = FrameConfiguration.from(
            photosUrl = updatedPhotosUrl,
            homeAssistantUrl = current.homeAssistantUrl,
            weatherEntityId = current.weatherEntityId,
            homeAssistantFallbackUrl = current.homeAssistantFallbackUrl,
            birdsUrl = current.birdsUrl,
        ) ?: return false
        configuration = updated
        configurationStore.write(updated)
        return true
    }

    private fun refreshPhotos() {
        rebuildExperienceUi()
        showMode(FrameMode.PHOTOS)
    }

    private fun rebuildExperienceUi() {
        cancelPhotoWork()
        hideOfflinePhoto()
        currentPhotoAssetId = null
        lastPhotoAt = null
        photoFallbackPolicy.recordFreshPhoto()
        webSurface?.destroy()
        buildUi()
        state = state.copy(mode = availability().resolve(state.mode))
        applyBrightness(state.brightnessOverridePercent)
        render(state, announce = false)
        scheduleIdleReset()
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
        val modes = availableModes()
        val position = modes.indexOf(positionMode).coerceAtLeast(0)
        handler.removeCallbacks(hideHud)
        hud.animate().cancel()
        hudLabel.text = message
        val hint = FrameControlHints.forMode(positionMode, state.photosPaused)
        val positionMarkers = modes.indices.joinToString("  ") { index -> if (index == position) "●" else "○" }
        hudPosition.text = getString(R.string.mode_position_with_hint, positionMarkers, hint)
        hud.contentDescription = "$message. ${position + 1} of ${modes.size}. $hint"
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
        // Idle return applies to other views; Photos pause/hold owns its own lifetime.
        if (state.mode != FrameMode.PHOTOS) {
            handler.postDelayed(expireIdle, experienceSettings.idleReturnSeconds * 1_000L)
        }
    }

    private fun movePhoto(forward: Boolean): Boolean {
        if (state.mode != FrameMode.PHOTOS || !activityResumed) return false
        if (photoFallbackPolicy.isShowing) return showOfflinePhoto(if (forward) 1 else -1, manual = true)
        if (state.photosPaused) {
            manualPhotoStepPending = true
            handler.removeCallbacks(finishManualPhotoStep)
            photoBridge.setCaptureEnabled(activityResumed, false)
        }
        val moved = webSurface?.movePhoto(forward) == true
        if (state.photosPaused) {
            if (moved) handler.postDelayed(finishManualPhotoStep, 8_000L) else finishManualPhotoStep.run()
        }
        return moved
    }

    private fun showOfflinePhoto(step: Int = 0, manual: Boolean = false): Boolean {
        if (!activityResumed || state.mode != FrameMode.PHOTOS || (state.photosPaused && !manual)) return false
        val entries = photoReserve.entries()
        if (entries.isEmpty()) { hideOfflinePhoto(); return false }
        fallbackIndex = Math.floorMod(fallbackIndex + step, entries.size)
        val entry = entries[fallbackIndex]
        val generation = ++photoDisplayGeneration
        handler.removeCallbacks(rotateOfflinePhoto)
        photoDecodeFuture?.cancel(true)
        photoDecodeFuture = photoWorkerExecutor.submit {
            if (generation != photoDisplayGeneration || Thread.currentThread().isInterrupted) return@submit
            val bitmap = runCatching { BitmapFactory.decodeFile(entry.file.path) }.getOrNull()
            handler.post {
                if (bitmap == null) return@post
                if (generation != photoDisplayGeneration || !activityResumed || state.mode != FrameMode.PHOTOS ||
                    !photoFallbackPolicy.isShowing || photoReserve.entries().none { it.assetId == entry.assetId }) {
                    bitmap.recycle()
                    return@post
                }
                val previous = offlineBitmap
                offlineBitmap = bitmap
                offlinePhoto.setImageBitmap(bitmap)
                previous?.recycle()
                currentPhotoAssetId = entry.assetId
                offlinePhoto.visibility = View.VISIBLE
                hideWebRecovery()
                showHud("Offline photos · reconnecting")
                if (!state.photosPaused) handler.postDelayed(rotateOfflinePhoto, OFFLINE_PHOTO_ROTATION_MILLIS)
            }
        }
        return true
    }

    private fun hideOfflinePhoto() {
        ++photoDisplayGeneration
        photoDecodeFuture?.cancel(true)
        photoDecodeFuture = null
        handler.removeCallbacks(rotateOfflinePhoto)
        if (::offlinePhoto.isInitialized) {
            offlinePhoto.visibility = View.GONE
            offlinePhoto.setImageDrawable(null)
        }
        offlineBitmap?.recycle()
        offlineBitmap = null
    }

    private fun cancelPhotoWork() {
        ++photoProbeGeneration
        ++photoDisplayGeneration
        photoProbeFuture?.cancel(true)
        photoDecodeFuture?.cancel(true)
        runCatching { activePhotoConnection?.disconnect() }
        activePhotoConnection = null
        photoProbeFuture = null
        photoDecodeFuture = null
        photoProbeInFlight = false
        healthyPhotoProbeAt = null
        manualPhotoStepPending = false
        handler.removeCallbacks(finishManualPhotoStep)
        photosWereActive = false
        handler.removeCallbacks(refreshPhotoFreshness)
        handler.removeCallbacks(rotateOfflinePhoto)
    }

    private fun schedulePhotoFreshness() {
        handler.removeCallbacks(refreshPhotoFreshness)
        val active = activityResumed && state.mode == FrameMode.PHOTOS && !state.photosPaused
        if (active && !photosWereActive) photosVisibleSince = System.currentTimeMillis()
        photosWereActive = active
        if (active && !photoProbeInFlight) handler.postDelayed(refreshPhotoFreshness, PHOTO_FRESHNESS_CHECK_MILLIS)
        if (!active) handler.removeCallbacks(rotateOfflinePhoto)
    }

    private fun checkPhotoFreshness() {
        if (!activityResumed || state.mode != FrameMode.PHOTOS || state.photosPaused || photoProbeInFlight) return
        val photosUrl = configuration?.photosUrl ?: return
        val generation = photoProbeGeneration
        photoProbeInFlight = true
        photoProbeFuture = photoWorkerExecutor.submit {
            if (generation != photoProbeGeneration || Thread.currentThread().isInterrupted) return@submit
            val reachable = runCatching {
                val source = java.net.URI(photosUrl)
                val url = java.net.URI(source.scheme, null, source.host, source.port, "/livez", null, null).toURL()
                val connection = url.openConnection() as java.net.HttpURLConnection
                activePhotoConnection = connection
                try {
                    if (generation != photoProbeGeneration || Thread.currentThread().isInterrupted) return@submit
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    connection.useCaches = false
                    connection.responseCode == 200
                } finally {
                    connection.disconnect()
                    if (activePhotoConnection === connection) activePhotoConnection = null
                }
            }.getOrDefault(false)
            val completedAt = System.currentTimeMillis()
            handler.post {
                if (generation != photoProbeGeneration) return@post
                photoProbeInFlight = false
                photoProbeFuture = null
                if (generation != photoProbeGeneration || !activityResumed || state.mode != FrameMode.PHOTOS ||
                    state.photosPaused || configuration?.photosUrl != photosUrl) {
                    schedulePhotoFreshness()
                    return@post
                }
                healthyPhotoProbeAt = completedAt.takeIf { reachable }
                if (reachable) photoFallbackPolicy.recordReachable()
                else if (photoFallbackPolicy.recordFailure(true, photoReserve.latest() != null) && offlinePhoto.visibility != View.VISIBLE) showOfflinePhoto()
                if (reachable && photoFallbackPolicy.shouldRecover(lastPhotoAt, photosVisibleSince, System.currentTimeMillis(), true, false)) {
                    diagnosticStore.recordRecovery("Photos data stale")
                    webSurface?.refreshPhotos()
                }
                schedulePhotoFreshness()
            }
        }
    }

    private fun applyBrightness(percent: Int?) {
        val attributes = window.attributes
        val now = LocalTime.now()
        val effectivePercent = QuietHoursBrightnessPolicy.effectivePercent(
            manualPercent = percent,
            quietHours = experienceSettings.quietHours,
            now = now,
        )
        attributes.screenBrightness = effectivePercent?.div(100f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
        handler.removeCallbacks(refreshQuietHours)
        if (activityResumed) {
            QuietHoursBrightnessPolicy.nextRefreshDelayMillis(experienceSettings.quietHours, now)?.let { delay ->
                handler.postDelayed(refreshQuietHours, delay)
            }
        }
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
        const val EVENT_OVERLAY_VISIBLE_MILLIS = 9_000L
        const val OFFLINE_PHOTO_ROTATION_MILLIS = 45_000L
        const val PHOTO_FRESHNESS_CHECK_MILLIS = 15_000L
        const val WEATHER_CACHE_FRESH_MILLIS = 5 * 60 * 1_000L
        const val OAUTH_EXCHANGE_TIMEOUT_MILLIS = 10_000L
        const val OAUTH_STATE_BYTES = 32
        const val DEFAULT_WEATHER_ENTITY_ID = "weather.home"
        val HOME_ASSISTANT_MODES = setOf(FrameMode.HOME, FrameMode.CALENDAR)
    }
}
