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
import android.net.Uri
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
import com.wyattfleming.frameos.navigation.ContextualFrameAction
import com.wyattfleming.frameos.navigation.ContextualInputMapper
import com.wyattfleming.frameos.navigation.PhysicalInputMapper
import com.wyattfleming.frameos.auth.AndroidKeystoreOAuthSessionStore
import com.wyattfleming.frameos.auth.HomeAssistantOAuthClient
import com.wyattfleming.frameos.auth.HomeAssistantOAuthEndpoint
import com.wyattfleming.frameos.auth.OAuthCallbackVerifier
import com.wyattfleming.frameos.auth.OAuthState
import com.wyattfleming.frameos.auth.PendingOAuthStateStore
import com.wyattfleming.frameos.auth.SharedPreferencesPendingOAuthStateStore
import com.wyattfleming.frameos.config.FrameConfiguration
import com.wyattfleming.frameos.config.FrameConfigurationStore
import com.wyattfleming.frameos.security.FrameExternalControlPolicy
import com.wyattfleming.frameos.ui.WeatherContentView
import com.wyattfleming.frameos.ui.dp
import com.wyattfleming.frameos.weather.HomeAssistantWeatherEndpoint
import com.wyattfleming.frameos.weather.HomeAssistantWeatherParser
import com.wyattfleming.frameos.weather.HomeAssistantWeatherRemote
import com.wyattfleming.frameos.weather.SharedPreferencesWeatherCache
import com.wyattfleming.frameos.weather.UrlConnectionHomeAssistantTransport
import com.wyattfleming.frameos.weather.WeatherCoordinator
import com.wyattfleming.frameos.weather.WeatherPresentation
import com.wyattfleming.frameos.weather.WeatherPresenter
import com.wyattfleming.frameos.weather.WeatherRepository
import com.wyattfleming.frameos.web.FrameSurfaceRouter
import com.wyattfleming.frameos.web.FrameSurfaceTarget
import com.wyattfleming.frameos.web.FrameWebSlot
import com.wyattfleming.frameos.web.FrameWebSurface
import java.net.URI
import java.security.SecureRandom
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private val reducer = FrameReducer()
    private val inputMapper = PhysicalInputMapper()
    private val contextualInputMapper = ContextualInputMapper()
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val secureRandom = SecureRandom()
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

    private var state = FrameState()
    private var lastRenderedMode = state.mode
    private var starLongPressed = false
    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private lateinit var configurationStore: FrameConfigurationStore
    private var configuration: FrameConfiguration? = null
    private var oauthClient: HomeAssistantOAuthClient? = null
    private var oauthEndpoint: HomeAssistantOAuthEndpoint? = null
    private var oauthCallbackPageUrl: String? = null
    private var pendingOAuthStateStore: PendingOAuthStateStore? = null
    private var callbackVerifier: OAuthCallbackVerifier? = null
    private var weatherCoordinator: WeatherCoordinator? = null
    private var weatherNeedsAuthentication = false
    private var weatherRequestGeneration = 0
    private var surfaceRouter: FrameSurfaceRouter? = null
    private var webSurface: FrameWebSurface? = null
    private val slowPageLoads = mutableMapOf<String, Runnable>()

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
        configurationStore = FrameConfigurationStore(this)
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
        showStartupTransition()
        scheduleIdleReset()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
        handleFrameCommand(intent)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        render(state, announce = false)
    }

    override fun onPause() {
        webSurface?.setContentActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        weatherRequestGeneration += 1
        ioExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        webSurface?.destroy()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                if (!starLongPressed) handleStarRelease(event)
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
        weatherContent = WeatherContentView(this).apply { visibility = View.GONE }
        val fullScreenLayout = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val activeConfiguration = configuration
        if (activeConfiguration != null) {
            val runtime = (application as FrameOsApplication).geckoRuntime
            val webListener = object : FrameWebSurface.Listener {
                override fun onPageLoading(label: String, loading: Boolean) {
                    runOnUiThread { updatePageLoading(label, loading) }
                }

                override fun onPageUnavailable(label: String) {
                    runOnUiThread {
                        updatePageLoading(label, false)
                        if (isWebLabelActive(label)) showHud("$label is temporarily unavailable")
                    }
                }
            }
            surfaceRouter = FrameSurfaceRouter(activeConfiguration)
            webSurface = FrameWebSurface(
                context = this,
                runtime = runtime,
                photosUrl = activeConfiguration.photosUrl,
                homeAssistantUrl = activeConfiguration.homeAssistantUrl,
                listener = webListener,
            )
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
        handler.postDelayed({
            val router = surfaceRouter ?: return@postDelayed
            webSurface?.preload(FrameWebSlot.HOME_ASSISTANT, router.homeAssistantRestingUrl())
        }, WEB_PRELOAD_DELAY_MILLIS)
    }

    private fun dispatch(intent: FrameIntent) {
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

    private fun render(next: FrameState, announce: Boolean) {
        val router = surfaceRouter ?: return
        val previousMode = lastRenderedMode
        lastRenderedMode = next.mode
        val restingUrl = router.homeAssistantRestingUrl().takeIf { previousMode == FrameMode.CAMERAS }
        when (val target = router.target(next.mode)) {
            is FrameSurfaceTarget.Web -> when (target.slot) {
                FrameWebSlot.PHOTOS -> {
                    weatherContent.visibility = View.GONE
                    webSurface?.show(target.slot, target.url, takeFocus = false)
                    webSurface?.setContentActive(!next.photosPaused)
                    root.requestFocus()
                }
                FrameWebSlot.HOME_ASSISTANT -> {
                    weatherContent.visibility = View.GONE
                    webSurface?.show(target.slot, target.url, takeFocus = true)
                }
            }
            FrameSurfaceTarget.NativeWeather -> {
                webSurface?.hide(restingUrl)
                weatherContent.visibility = View.VISIBLE
                root.requestFocus()
                if (announce) weatherContent.animate().alpha(0.82f).setDuration(70).withEndAction {
                    weatherContent.animate().alpha(1f).setDuration(180).start()
                }.start()
            }
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
        else -> false
    }

    private fun handleFrameCommand(intent: Intent) {
        if (!externalControlPolicy.acceptsCommands()) {
            intent.removeExtra(EXTRA_FRAME_COMMAND)
            intent.removeExtra(EXTRA_FRAME_MODE)
            return
        }
        val command = intent.getStringExtra(EXTRA_FRAME_COMMAND)?.lowercase(Locale.ROOT)
        val requestedMode = intent.getStringExtra(EXTRA_FRAME_MODE)?.uppercase(Locale.ROOT)
            ?.let { name -> FrameMode.entries.firstOrNull { it.name == name } }
        intent.removeExtra(EXTRA_FRAME_COMMAND)
        intent.removeExtra(EXTRA_FRAME_MODE)

        when {
            requestedMode != null -> showMode(requestedMode)
            command == "next" -> dispatch(FrameIntent.NextMode)
            command == "previous" || command == "prev" -> dispatch(FrameIntent.PreviousMode)
            command == "home" -> dispatch(FrameIntent.GoHome)
        }
    }

    private fun showMode(mode: FrameMode) {
        val changed = state.mode != mode
        state = state.copy(
            mode = mode,
            photosPaused = if (mode == FrameMode.PHOTOS) false else state.photosPaused,
        )
        render(state, announce = changed)
        if (mode == FrameMode.WEATHER) refreshWeather()
        if (changed) showHud()
        scheduleIdleReset()
    }

    private fun persistProvisioning(intent: Intent): FrameConfiguration? {
        if (!intent.hasExtra(EXTRA_PHOTOS_URL) && !intent.hasExtra(EXTRA_HOME_ASSISTANT_URL)) return null
        val provisioned = FrameConfiguration.from(
            photosUrl = intent.getStringExtra(EXTRA_PHOTOS_URL).orEmpty(),
            homeAssistantUrl = intent.getStringExtra(EXTRA_HOME_ASSISTANT_URL).orEmpty(),
            weatherEntityId = intent.getStringExtra(EXTRA_WEATHER_ENTITY_ID).orEmpty().ifBlank { DEFAULT_WEATHER_ENTITY_ID },
        ) ?: return null
        configurationStore.write(provisioned)
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
            )
            oauthEndpoint = endpoint
            oauthCallbackPageUrl = callbackPageUrl
            pendingOAuthStateStore = pendingStateStore
            oauthClient = client
            callbackVerifier = OAuthCallbackVerifier(pendingStateStore)
            weatherCoordinator = WeatherCoordinator(
                accessTokenProvider = client,
                repository = WeatherRepository(
                    remote = HomeAssistantWeatherRemote(
                        endpoint = HomeAssistantWeatherEndpoint.fromDisplayUrl(activeConfiguration.homeAssistantUrl),
                        transport = transport,
                        parser = HomeAssistantWeatherParser(),
                    ),
                    cache = SharedPreferencesWeatherCache(this),
                    clock = System::currentTimeMillis,
                    freshForMillis = WEATHER_CACHE_FRESH_MILLIS,
                ),
                presenter = WeatherPresenter(ZoneId.systemDefault(), Locale.getDefault()),
                entityId = activeConfiguration.weatherEntityId,
            )
            weatherNeedsAuthentication = sessionStore.read() == null
            weatherContent.presentation = WeatherPresentation(
                emptyMessage = if (weatherNeedsAuthentication) {
                    "Weather needs Home Assistant sign-in"
                } else {
                    "Loading the latest forecast"
                },
            )
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            weatherNeedsAuthentication = false
            weatherContent.presentation = WeatherPresentation(
                emptyMessage = "Weather needs a secure Home Assistant URL",
            )
        }
    }

    private fun refreshWeather() {
        val coordinator = weatherCoordinator ?: return
        val generation = ++weatherRequestGeneration
        ioExecutor.execute {
            val presentation = try {
                coordinator.refresh()
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                WeatherPresentation(emptyMessage = "Weather is temporarily unavailable")
            }
            handler.post {
                if (generation != weatherRequestGeneration || isFinishing || isDestroyed) return@post
                weatherContent.presentation = presentation
                weatherNeedsAuthentication = presentation.emptyMessage == "Weather needs Home Assistant sign-in"
            }
        }
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
        val client = oauthClient ?: return
        val generation = ++weatherRequestGeneration
        ioExecutor.execute {
            val success = client.exchangeAuthorizationCode(code.code)
            handler.post {
                if (generation != weatherRequestGeneration || isFinishing || isDestroyed) return@post
                if (success) {
                    refreshWeather()
                } else {
                    weatherNeedsAuthentication = true
                    weatherContent.presentation = WeatherPresentation(emptyMessage = "Weather needs Home Assistant sign-in")
                    showHud("Home Assistant sign-in failed")
                }
            }
        }
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
        surface.requestFocus()
        return surface.dispatchKeyEvent(event)
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
        const val WEB_PRELOAD_DELAY_MILLIS = 2_000L
        const val SLOW_PAGE_LOAD_MILLIS = 450L
        const val HUD_FADE_IN_MILLIS = 160L
        const val HUD_FADE_OUT_MILLIS = 220L
        const val HUD_VISIBLE_MILLIS = 1_500L
        const val IDLE_TIMEOUT_MILLIS = 15 * 60 * 1_000L
        const val WEATHER_CACHE_FRESH_MILLIS = 5 * 60 * 1_000L
        const val OAUTH_STATE_BYTES = 32
        const val DEFAULT_WEATHER_ENTITY_ID = "weather.home"
        const val EXTRA_PHOTOS_URL = "frameos.photos_url"
        const val EXTRA_HOME_ASSISTANT_URL = "frameos.home_assistant_url"
        const val EXTRA_WEATHER_ENTITY_ID = "frameos.weather_entity_id"
        const val EXTRA_FRAME_COMMAND = "frameos.command"
        const val EXTRA_FRAME_MODE = "frameos.mode"
        val HOME_ASSISTANT_MODES = setOf(FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR)
    }
}
