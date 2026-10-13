package com.wyattfleming.touchframe

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.wyattfleming.frameos.auth.*
import com.wyattfleming.frameos.config.FrameExperienceSettings
import com.wyattfleming.frameos.config.FrameExperienceStore
import com.wyattfleming.frameos.config.QuietHoursBrightnessPolicy
import com.wyattfleming.frameos.control.*
import com.wyattfleming.frameos.navigation.FrameMode
import com.wyattfleming.frameos.ui.WeatherContentView
import com.wyattfleming.frameos.weather.*
import com.wyattfleming.frameos.web.FrameOAuthWebSurface
import com.wyattfleming.touchframe.concurrent.TouchTaskScope
import com.wyattfleming.touchframe.calendar.*
import com.wyattfleming.touchframe.control.TouchCompanionController
import com.wyattfleming.touchframe.control.TouchRemoteSettingsPolicy
import com.wyattfleming.touchframe.data.TouchConfiguration
import com.wyattfleming.touchframe.data.TouchConfigurationStore
import com.wyattfleming.touchframe.home.*
import com.wyattfleming.touchframe.media.TouchCameraView
import com.wyattfleming.touchframe.media.TouchMediaView
import com.wyattfleming.touchframe.media.TouchOfflinePhotoStore
import com.wyattfleming.touchframe.ui.*
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Touch navigation and native Home own this activity; the Lenovo activity is never hosted. */
class TouchFrameActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val authorizationWorker = Executors.newSingleThreadExecutor()
    private val tokenLock = Any()
    private val tasks = TouchTaskScope()
    private val cancellationWorker = Executors.newSingleThreadExecutor()
    private lateinit var homeCache: TouchHomeCache
    private var cacheLoaded = false
    private lateinit var photoStore: TouchOfflinePhotoStore
    private lateinit var companion: TouchCompanionController
    private lateinit var experienceStore: FrameExperienceStore
    private var experience = FrameExperienceSettings()
    private var photosPaused = false
    private var holdUntilMillis = 0L
    private var lastInteractionMillis = SystemClock.elapsedRealtime()
    private var lastAppliedBrightness: Float? = null
    private var currentAssetId: String? = null
    private var lastPhotoAt: Long? = null
    private var lastMediaError: String? = null
    private val runtimePreferences by lazy { getSharedPreferences("touchframe_runtime", MODE_PRIVATE) }
    private var resumed = false
    private var refreshInFlight = false
    private var weatherInFlight = false
    private var climateInFlight = false
    private var destination = TouchDestination.HOME
    private var configuration: TouchConfiguration? = null
    private var repository: TouchHomeRepository? = null
    private var calendarRepository: TouchCalendarRepository? = null
    private var calendarInFlight = false
    private var calendarRequestGeneration = 0L
    private var oauth: HomeAssistantOAuthClient? = null
    private var weatherRemote: HomeAssistantWeatherRemote? = null
    private var weatherSnapshot: WeatherSnapshot? = null
    private var lastWeatherAt: Long? = null
    private var model = TouchHomeModel("", "", emptyList(), null, TouchConnection.REFRESHING, null)
    private lateinit var root: FrameLayout
    private lateinit var shell: TouchShellLayout
    private lateinit var home: TouchHomeView
    private lateinit var weather: WeatherContentView
    private lateinit var calendar: TouchCalendarView
    private var calendarState = TouchCalendarState(TouchCalendarMode.AGENDA, java.time.LocalDate.now(), java.time.LocalDate.now(), TouchCalendarRange.forMode(TouchCalendarMode.AGENDA, java.time.LocalDate.now()), emptyList(), TouchCalendarConnection.OFFLINE, null)
    private lateinit var settingsReturn: TouchSettingsReturn
    private var media: TouchMediaView? = null
    private var cameras: TouchCameraView? = null
    private var authorization: FrameOAuthWebSurface? = null
    private var authorizationContainer: View? = null
    private var pendingAuthorization: SharedPreferencesPendingOAuthStateStore? = null
    private var lanPromptShown = false
    private val runtime get() = (application as TouchFrameApplication).runtime
    private val clock = object : Runnable {
        override fun run() {
            if (!resumed) return
            val now = System.currentTimeMillis()
            home.tick(now)
            updateDisplayBehavior(now)
            val hour = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour
            shell.setNight(hour < 7 || hour >= 19)
            calendar.setNight(hour < 7 || hour >= 19)
            main.postDelayed(this, 1_000L)
        }
    }
    private val refresh = Runnable {
        when (destination) {
            TouchDestination.HOME -> refreshHome()
            TouchDestination.CALENDAR -> requestCalendar(calendarState.mode, calendarState.anchor, calendarState.selectedDate)
            TouchDestination.WEATHER -> refreshWeather()
            else -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        configuration = TouchConfigurationStore(this).read()
        homeCache = TouchHomeCache(getSharedPreferences("touch_home_cache", MODE_PRIVATE))
        experienceStore = FrameExperienceStore(this)
        experience = experienceStore.read()
        photoStore = TouchOfflinePhotoStore(this)
        configuration?.let { photoStore.activateScope(it.photosUrl, photoProfile(it)) }
        settingsReturn = TouchSettingsReturn(this)
        root = FrameLayout(this)
        shell = TouchShellLayout(this, ::navigate) {
            if (!settingsReturn.open()) message("Enable the FrameOS Touch return button before opening Android Settings")
        }
        home = TouchHomeView(this, { route ->
            TouchDestination.entries.firstOrNull { it.name.equals(route, true) }?.let(::navigate)
        }, ::signIn, { mode -> changeClimate { it.changeClimateMode(mode) } },
            { value -> changeClimate { it.changeClimateTarget(value) } })
        weather = WeatherContentView(this).apply {
            presentation = WeatherPresentation(emptyMessage = "Sign in to Home Assistant for weather", authenticationRequired = true)
            setOnPrimaryActionListener(::signIn)
            visibility = View.GONE
        }
        calendar = TouchCalendarView(this, ::requestCalendar, ::signIn).apply { visibility = View.GONE }
        shell.content.addView(home, fill())
        shell.content.addView(weather, fill())
        shell.content.addView(calendar, fill())
        root.addView(shell, fill())
        setContentView(root)
        companion = TouchCompanionController.create(
            applicationContext,
            statusProvider = ::companionStatus,
            onCommand = { command, _ -> applyRemoteCommand(command) },
            onSettings = ::applyRemoteSettings,
            onEvent = ::showRemoteEvent,
            onHiddenAssets = ::applyHiddenAssets,
        )
        applyDestinationOrder()
        home.render(model)
        calendar.render(calendarState)
        if (configuration == null) message("This frame needs its connection configuration")
    }

    private fun initializeData() {
        val config = configuration ?: return
        val callback = config.homeAssistantOrigin + HomeAssistantOAuthEndpoint.CALLBACK_PATH
        val client = HomeAssistantOAuthClient(
            HomeAssistantOAuthEndpoint.fromDisplayUrl(config.homeAssistantUrl), callback,
            UrlConnectionHomeAssistantTransport(), AndroidKeystoreOAuthSessionStore(this, callback),
            System::currentTimeMillis,
        )
        oauth = client
        repository = TouchHomeRepository(config, TouchHomeTokenProvider { deadline ->
            synchronized(tokenLock) { if (Thread.currentThread().isInterrupted) null else client.validAccessToken(deadline) }
        })
        calendarRepository = TouchCalendarRepository(config, TouchCalendarTokenProvider { deadline ->
            synchronized(tokenLock) { if (Thread.currentThread().isInterrupted) null else client.validAccessToken(deadline) }
        })
        weatherRemote = HomeAssistantWeatherRemote(
            HomeAssistantWeatherEndpoint.fromDisplayUrl(config.homeAssistantUrl),
            UrlConnectionHomeAssistantTransport(), HomeAssistantWeatherParser(),
        )
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        tasks.resume()
        initializeData()
        settingsReturn.remove()
        immersive()
        main.removeCallbacks(clock)
        clock.run()
        navigate(destination)
        companion.setEnabled(networkAllowed() && runtimePreferences.getBoolean("companion_enabled", false))
        companion.start()
        if (!networkAllowed() && !lanPromptShown) requestLocalNetwork()
    }

    override fun onPause() {
        resumed = false
        tasks.pause()
        companion.stop()
        main.removeCallbacks(clock)
        main.removeCallbacks(refresh)
        refreshInFlight = false
        calendarInFlight = false
        weatherInFlight = false
        climateInFlight = false
        home.setClimateBusy(false)
        releaseData()
        media?.setVisibleActive(false)
        cameras?.setActive(false)
        closeAuthorization(restoreDestination = false)
        super.onPause()
    }

    override fun onDestroy() {
        settingsReturn.remove()
        closeAuthorization(restoreDestination = false)
        tasks.close()
        companion.close()
        releaseData()
        media?.close()
        photoStore.close()
        cameras?.close()
        worker.shutdownNow()
        authorizationWorker.shutdownNow()
        cancellationWorker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        shell.select(destination)
    }

    private fun releaseData() {
        // Cancellation may disconnect a socket. Keep that work away from touch dispatch and
        // capture these exact instances so a resumed activity's new clients are unaffected.
        val oldRepository = repository
        val oldCalendarRepository = calendarRepository
        val oldWeather = weatherRemote
        val oldOAuth = oauth
        repository = null
        calendarRepository = null
        weatherRemote = null
        oauth = null
        cancellationWorker.execute {
            oldRepository?.close()
            oldCalendarRepository?.close()
            oldWeather?.cancel()
            oldOAuth?.cancelInFlight()
        }
    }

    private fun navigate(next: TouchDestination) {
        if (next.usesWebMedia && webMediaUrl(next) == null) {
            message("${next.label} is not configured")
            return
        }
        if (next == TouchDestination.CAMERAS && configuration == null) { message("Cameras are not configured"); return }
        if ((next.usesWebMedia || next == TouchDestination.CAMERAS) && !networkAllowed()) {
            requestLocalNetwork()
            return
        }
        if (destination == TouchDestination.CALENDAR && next != TouchDestination.CALENDAR) {
            calendarRequestGeneration++
            calendarInFlight = false
            calendarRepository?.let { stale -> cancellationWorker.execute { if (destination != TouchDestination.CALENDAR) stale.cancel() } }
        }
        destination = next
        shell.select(next)
        home.visibility = if (next == TouchDestination.HOME) View.VISIBLE else View.GONE
        weather.visibility = if (next == TouchDestination.WEATHER) View.VISIBLE else View.GONE
        calendar.visibility = if (next == TouchDestination.CALENDAR) View.VISIBLE else View.GONE
        cameras?.visibility = if (next == TouchDestination.CAMERAS) View.VISIBLE else View.GONE
        cameras?.setActive(resumed && networkAllowed() && next == TouchDestination.CAMERAS)
        media?.setVisibleActive(resumed && networkAllowed() && next.usesWebMedia)
        main.removeCallbacks(refresh)
        when (next) {
            TouchDestination.HOME -> refreshHome()
            TouchDestination.CALENDAR -> requestCalendar(calendarState.mode, calendarState.anchor, calendarState.selectedDate)
            TouchDestination.WEATHER -> refreshWeather()
            TouchDestination.PHOTOS, TouchDestination.ART, TouchDestination.BIRDS -> {
                val url = webMediaUrl(next) ?: run { message("${next.label} is not configured"); return }
                if (!networkAllowed()) { requestLocalNetwork(); return }
                val surface = media ?: TouchMediaView(this, runtime, photoStore = photoStore).also {
                    media = it
                    it.onStatus = { status ->
                        lastMediaError = status
                        if (destination.usesWebMedia && status != null) message(status)
                    }
                    it.onPhotoObserved = { photo ->
                        currentAssetId = photo.assetId
                        lastPhotoAt = System.currentTimeMillis()
                        lastMediaError = null
                    }
                    // Native panels cover this retained view; Home never reattaches a browser surface.
                    shell.content.addView(it, 0, fill())
                }
                if (next == TouchDestination.PHOTOS) {
                    surface.showPhotos(url, photoProfile(requireNotNull(configuration)))
                    surface.setPhotosPaused(photosPaused)
                } else if (next == TouchDestination.ART) surface.showArt(url)
                else surface.showUrl(url, "Birds")
                surface.setVisibleActive(resumed)
            }
            TouchDestination.CAMERAS -> {
                val config = configuration ?: return
                if (!networkAllowed()) { requestLocalNetwork(); return }
                val view = cameras ?: TouchCameraView(this, config.homeAssistantOrigin, config.cameraEntityIds) { deadline ->
                    synchronized(tokenLock) {
                        if (Thread.currentThread().isInterrupted) null else oauth?.validAccessToken(deadline)
                    }
                }.also {
                    cameras = it
                    it.onSignIn = ::signIn
                    shell.content.addView(it, fill())
                }
                view.visibility = View.VISIBLE
                view.setActive(resumed)
            }
        }
    }

    private fun webMediaUrl(destination: TouchDestination): String? = when (destination) {
        TouchDestination.PHOTOS -> configuration?.photosUrl
        TouchDestination.ART -> configuration?.artUrl
        TouchDestination.BIRDS -> configuration?.birdsUrl
        else -> null
    }

    private fun refreshHome() {
        val source = repository ?: return
        val config = configuration ?: return
        if (!resumed || refreshInFlight || climateInFlight || !networkAllowed()) return
        refreshInFlight = true
        val previous = model
        val shouldReadCache = !cacheLoaded
        tasks.submit(worker) { isCurrent ->
            val cached = if (shouldReadCache) runCatching { homeCache.read(config.homeAssistantOrigin, cacheEntities(config)) }.getOrNull() else null
            if (!isCurrent()) return@submit
            if (cached != null) main.post {
                if (!isCurrent()) return@post
                model = cached
                home.render(cached)
                if (destination == TouchDestination.CALENDAR) calendar.render(calendarState)
            }
            val fallback = cached ?: previous
            val next = runCatching { source.refresh(fallback) }.getOrElse { fallback.copy(connection = TouchConnection.OFFLINE) }
            if (!isCurrent()) return@submit
            if (next.connection == TouchConnection.CONNECTED) runCatching { homeCache.write(config.homeAssistantOrigin, cacheEntities(config), next) }
            main.post {
                if (!isCurrent()) return@post
                cacheLoaded = true
                refreshInFlight = false
                model = next
                home.render(next)
                if (destination == TouchDestination.CALENDAR) calendar.render(calendarState)
                main.removeCallbacks(refresh)
                main.postDelayed(refresh, 60_000L)
            }
        }
    }

    private fun requestCalendar(mode: TouchCalendarMode, anchor: java.time.LocalDate, selected: java.time.LocalDate) {
        val source = calendarRepository ?: return
        if (!resumed || !networkAllowed()) return
        val range = TouchCalendarRange.forMode(mode, anchor)
        val selectedForRange = if (selected < range.start || selected >= range.endExclusive) anchor else selected
        // A selected day inside an already-fetched range is local UI state, not another network request.
        if (!calendarState.loading && calendarState.range == range && calendarState.events.isNotEmpty()) {
            calendarState = calendarState.copy(mode = mode, anchor = anchor, selectedDate = selectedForRange)
            calendar.render(calendarState)
            return
        }
        val prior = calendarState
        val request = ++calendarRequestGeneration
        calendarInFlight = true
        // The new worker operation cancels its predecessor; avoid socket teardown on touch dispatch.
        calendarState = calendarState.copy(mode = mode, anchor = anchor, selectedDate = selectedForRange, range = range, loading = true, partial = false)
        calendar.render(calendarState)
        tasks.submit(worker) { isCurrent ->
            val loaded = runCatching { source.load(range) }.getOrElse { TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE) }
            if (!isCurrent() || request != calendarRequestGeneration || destination != TouchDestination.CALENDAR) return@submit
            main.post {
                if (!isCurrent() || request != calendarRequestGeneration || destination != TouchDestination.CALENDAR) return@post
                calendarInFlight = false
                // Offline retention is valid only for exactly the range that was requested.
                val retained = if (loaded.connection == TouchCalendarConnection.OFFLINE && prior.range == range) prior.events else loaded.events
                calendarState = calendarState.copy(events = retained, connection = loaded.connection, updatedEpochMillis = loaded.updatedEpochMillis ?: if (prior.range == range) prior.updatedEpochMillis else null, loading = false, partial = loaded.partial)
                calendar.render(calendarState)
            }
        }
    }

    private fun changeClimate(command: (TouchHomeRepository) -> Boolean) {
        val source = repository ?: return
        if (!resumed || climateInFlight || !networkAllowed()) return
        climateInFlight = true
        home.setClimateBusy(true, "Applying…")
        val previous = model
        tasks.submit(worker) { isCurrent ->
            if (!isCurrent()) return@submit
            val accepted = runCatching { command(source) }.getOrDefault(false)
            val next = if (accepted) runCatching { source.refresh(previous) }.getOrNull() else null
            main.post {
                if (!isCurrent()) return@post
                climateInFlight = false
                if (next != null) { model = next; home.render(next) }
                home.setClimateBusy(false, if (accepted && next?.connection == TouchConnection.CONNECTED)
                    "Updated" else "Could not confirm the change. Please retry.")
            }
        }
    }

    private fun refreshWeather() {
        val config = configuration ?: return
        val remote = weatherRemote ?: return
        val client = oauth ?: return
        if (!resumed || weatherInFlight || !networkAllowed()) return
        weatherInFlight = true
        tasks.submit(worker) { isCurrent ->
            val deadline = WeatherRequestDeadline(timeoutMillis = 12_000L)
            val result = runCatching {
                val token = synchronized(tokenLock) { if (isCurrent()) client.validAccessToken(deadline) else null }
                if (token == null) WeatherRemoteResult.AuthRequired("Sign-in needed")
                else if (isCurrent()) remote.fetch(config.weatherEntityId, token, deadline) else WeatherRemoteResult.Offline("Cancelled")
            }.getOrElse { WeatherRemoteResult.Offline("Unavailable") }
            main.post {
                if (!isCurrent()) return@post
                weatherInFlight = false
                val load = when (result) {
                    is WeatherRemoteResult.Success -> WeatherLoadResult.Fresh(result.snapshot).also {
                        weatherSnapshot = result.snapshot
                        lastWeatherAt = System.currentTimeMillis()
                    }
                    is WeatherRemoteResult.AuthRequired -> weatherSnapshot?.let { WeatherLoadResult.Stale(it, true) } ?: WeatherLoadResult.AuthRequired
                    is WeatherRemoteResult.Offline -> weatherSnapshot?.let { WeatherLoadResult.Stale(it) } ?: WeatherLoadResult.Offline
                }
                weather.presentation = WeatherPresenter(ZoneId.systemDefault(), Locale.getDefault()).present(load).let {
                    if (load == WeatherLoadResult.AuthRequired) it.copy(authenticationRequired = true) else it
                }
                if (destination == TouchDestination.WEATHER) {
                    main.removeCallbacks(refresh)
                    main.postDelayed(refresh, 60_000L)
                }
            }
        }
    }

    private fun signIn() {
        val config = configuration ?: return
        val client = oauth ?: return
        if (authorization != null || !resumed) return
        if (!networkAllowed()) { requestLocalNetwork(); return }
        val callback = config.homeAssistantOrigin + HomeAssistantOAuthEndpoint.CALLBACK_PATH
        val pending = SharedPreferencesPendingOAuthStateStore(this)
        val state = OAuthState.fromCryptographicBytes(ByteArray(32).also(SecureRandom()::nextBytes))
        pending.write(state)
        pendingAuthorization = pending
        val request = HomeAssistantOAuthEndpoint.fromDisplayUrl(config.homeAssistantUrl).authorizationRequest(callback, state)
        val verifier = OAuthCallbackVerifier(pending, callback)
        val container = FrameLayout(this).apply { setBackgroundColor(PAPER) }
        val surface = FrameOAuthWebSurface(this, runtime, request.url, config.homeAssistantUrl, callback, { url ->
            val code = verifier.verify(url)
            closeAuthorization(restoreDestination = false)
            if (code == null) { message("Sign-in could not be verified"); navigate(destination) }
            else {
                tasks.submit(authorizationWorker) { isCurrent ->
                    val deadline = WeatherRequestDeadline(timeoutMillis = 12_000L)
                    val success = synchronized(tokenLock) { isCurrent() && client.exchangeAuthorizationCode(code.code, deadline) }
                    main.post {
                        if (!isCurrent()) return@post
                        message(if (success) "Connected to Home Assistant" else "Sign-in could not be completed")
                        navigate(destination)
                    }
                }
            }
        }, { closeAuthorization(); message("Sign-in could not be completed") })
        container.addView(surface, fill())
        container.addView(Button(this).apply {
            text = "Cancel sign-in"
            minHeight = dp(56)
            setOnClickListener { pending.consume(); closeAuthorization() }
        }, FrameLayout.LayoutParams(-2, dp(56), Gravity.TOP or Gravity.END))
        authorization = surface
        authorizationContainer = container
        media?.setVisibleActive(false)
        cameras?.setActive(false)
        root.addView(container, fill())
        runCatching { surface.start() }.onFailure { closeAuthorization(); message("Sign-in is unavailable") }
    }

    private fun closeAuthorization(restoreDestination: Boolean = true) {
        val hadAuthorization = authorization != null
        pendingAuthorization?.consume()
        pendingAuthorization = null
        authorization?.close()
        authorization = null
        authorizationContainer?.let(root::removeView)
        authorizationContainer = null
        if (hadAuthorization && restoreDestination && resumed) navigate(destination)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionMillis = SystemClock.elapsedRealtime()
    }

    private fun updateDisplayBehavior(now: Long) {
        val brightness = QuietHoursBrightnessPolicy.effectivePercent(null, experience.quietHours,
            Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime())?.div(100f)
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lastAppliedBrightness != brightness) {
            window.attributes = window.attributes.apply { screenBrightness = brightness }
            lastAppliedBrightness = brightness
        }
        if (holdUntilMillis > 0 && now >= holdUntilMillis) {
            holdUntilMillis = 0
            photosPaused = false
            media?.setPhotosPaused(false)
        }
        if (authorization == null && destination != TouchDestination.ART && destination != TouchDestination.PHOTOS &&
            configuration != null && SystemClock.elapsedRealtime() - lastInteractionMillis >= experience.idleReturnSeconds * 1_000L) {
            lastInteractionMillis = SystemClock.elapsedRealtime()
            navigate(TouchDestination.PHOTOS)
        }
    }

    private fun photoProfile(config: TouchConfiguration): String = FramePhotosProfileUrl.profile(config.photosUrl) ?: "balanced"

    private fun companionStatus(): FrameRemoteStatus? {
        if (!resumed) return null
        val reserve = photoStore.publishedStatus()
        return FrameRemoteStatus(
            mode = FrameMode.valueOf(destination.name), photosPaused = photosPaused,
            lastPaintAt = null, lastWeatherAt = lastWeatherAt,
            recoveryCount = 0, lastError = lastMediaError, offline = media?.offlineVisible == true,
            appVersion = "touch-0.1.0", currentAssetId = currentAssetId, lastPhotoAt = lastPhotoAt,
            profile = configuration?.let(::photoProfile), offlineAssets = reserve.offlineAssets, offlineBytes = reserve.offlineBytes,
        )
    }

    private fun applyRemoteCommand(command: FrameRemoteCommand): TouchCompanionController.CommandResult {
        if (!resumed || authorization != null) return TouchCompanionController.CommandResult.Failed("Frame is not ready")
        lastInteractionMillis = SystemClock.elapsedRealtime()
        return when (command) {
            is FrameRemoteCommand.ShowMode -> {
                val route = TouchDestination.entries.firstOrNull { it.name == command.mode.name }
                    ?: return TouchCompanionController.CommandResult.Unsupported("Mode is unavailable on this frame")
                navigate(route)
                if (destination == route) TouchCompanionController.CommandResult.Applied()
                else TouchCompanionController.CommandResult.Failed("Mode is not configured")
            }
            is FrameRemoteCommand.PhotoStep -> {
                if (destination != TouchDestination.PHOTOS || media?.movePhoto(command.forward) != true)
                    TouchCompanionController.CommandResult.Failed("Photos is not ready")
                else TouchCompanionController.CommandResult.Dispatched()
            }
            is FrameRemoteCommand.PhotoPause -> {
                photosPaused = command.paused
                holdUntilMillis = 0
                val sent = media?.setPhotosPaused(photosPaused) == true
                if (sent) TouchCompanionController.CommandResult.Dispatched()
                else TouchCompanionController.CommandResult.Applied("Pause applied for this session")
            }
            is FrameRemoteCommand.PhotoHold -> {
                photosPaused = true
                holdUntilMillis = System.currentTimeMillis() + command.durationSeconds * 1_000L
                val sent = media?.setPhotosPaused(true) == true
                if (sent) TouchCompanionController.CommandResult.Dispatched()
                else TouchCompanionController.CommandResult.Applied("Hold applied for this session")
            }
            is FrameRemoteCommand.SetProfile -> {
                if (updateProfile(command.profile)) TouchCompanionController.CommandResult.Dispatched()
                else TouchCompanionController.CommandResult.Failed("Profile is unavailable")
            }
        }
    }

    private fun updateProfile(profile: String): Boolean {
        val previous = configuration ?: return false
        val url = FramePhotosProfileUrl.withProfile(previous.photosUrl, profile) ?: return false
        if (url == previous.photosUrl) return true
        val next = previous.copy(photosUrl = url)
        configuration = next
        TouchConfigurationStore(this).write(next)
        currentAssetId = null
        lastPhotoAt = null
        photoStore.activateScope(url, profile)
        if (destination == TouchDestination.PHOTOS) navigate(TouchDestination.PHOTOS)
        return true
    }

    private fun applyRemoteSettings(settings: FrameRemoteSettings, token: Long): Boolean {
        if (!companion.isCurrent(token)) return false
        if (!TouchRemoteSettingsPolicy.shouldApply(experience.settingsRevision, settings.revision)) return true
        if (!updateProfile(settings.profile)) return false
        experience = FrameExperienceSettings(
            orderedEnabledModes = settings.modeOrder, quietHours = settings.quietHours,
            deactivateHiddenHomeAssistant = settings.hiddenHomeSuspend,
            idleReturnSeconds = settings.idleReturnSeconds, eventOverlaysEnabled = settings.eventOverlaysEnabled,
            settingsRevision = settings.revision,
        )
        experienceStore.write(experience)
        applyDestinationOrder()
        updateDisplayBehavior(System.currentTimeMillis())
        return true
    }

    private fun applyDestinationOrder() {
        val birdsConfigured = !configuration?.birdsUrl.isNullOrBlank()
        val requested = if (experience.settingsRevision >= 0) {
            experience.orderedEnabledModes.mapNotNull { mode ->
                TouchDestination.entries.firstOrNull { it.name == mode.name }
            }
        } else {
            TouchDestinationOrder.defaults(birdsConfigured)
        }
        val destinations = TouchDestinationOrder.available(requested, birdsConfigured)
        home.setDestinations(destinations)
        shell.setDestinations(destinations)
    }

    /** Companion worker waits for the owned reserve worker, then for a bounded UI commit. */
    private fun applyHiddenAssets(ids: Set<String>, token: Long, canCommit: () -> Boolean): Boolean {
        val active = AtomicBoolean(true)
        val current = { active.get() && canCommit() && companion.isCurrent(token) }
        val update = photoStore.applyHiddenAssets(ids, current)
        val applied = try {
            update.get(8, TimeUnit.SECONDS)
        } catch (_: Exception) {
            active.set(false)
            update.cancel(true)
            false
        }
        if (!applied || !current()) return false
        val accepted = onCompanionMain(current) {
            // Called on main: hidden cached content is removed synchronously.
            media?.hideOfflinePhotoIfHidden(ids)
            if (currentAssetId?.lowercase(Locale.ROOT) in ids) {
                currentAssetId = null
                if (destination == TouchDestination.PHOTOS) media?.movePhoto(true)
            }
            true
        } == true
        active.set(false)
        return accepted
    }

    /** Event persistence is owned by the companion worker; only presentation touches main. */
    private fun showRemoteEvent(event: FrameCompanionEvent, token: Long, canCommit: () -> Boolean): Boolean {
        val current = { canCommit() && companion.isCurrent(token) }
        val eligible = onCompanionMain(current) { eventMayShow() } ?: return false
        if (!eligible) return true
        if (!current()) return false
        return when (FrameCompanionEventStore(applicationContext).claimResult(event.id, System.currentTimeMillis())) {
            FrameCompanionEventStore.ClaimResult.PERSISTENCE_FAILED -> false
            FrameCompanionEventStore.ClaimResult.ALREADY_HANDLED -> current()
            FrameCompanionEventStore.ClaimResult.CLAIMED -> onCompanionMain(current) {
                if (eventMayShow()) message(event.text)
                true
            } == true
        }
    }

    private fun eventMayShow(): Boolean = resumed && authorization == null && experience.eventOverlaysEnabled &&
        destination == TouchDestination.PHOTOS && !photosPaused &&
        experience.quietHours?.isActiveAt(java.time.LocalTime.now()) != true

    /** Never waits on main; cancels a queued delivery when its worker stops waiting. */
    private fun <T> onCompanionMain(canCommit: () -> Boolean, action: () -> T): T? {
        check(Looper.myLooper() != Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val delivery = FutureTask<T?> { if (active.get() && canCommit()) action() else null }
        main.post(delivery)
        return try {
            delivery.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            active.set(false)
            delivery.cancel(false)
            null
        }
    }

    private fun cacheEntities(config: TouchConfiguration): List<String> =
        listOf(config.weatherEntityId, config.climateEntityId) + config.calendarEntityIds

    private fun networkAllowed(): Boolean = Build.VERSION.SDK_INT < 37 || checkSelfPermission(LAN_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < 37 || networkAllowed()) return
        lanPromptShown = true
        AlertDialog.Builder(this).setTitle("Connect to your home")
            .setMessage("FrameOS Touch needs local network access to reach your frame services and Home Assistant.")
            .setPositiveButton("Allow") { _, _ -> requestPermissions(arrayOf(LAN_PERMISSION), LAN_REQUEST) }
            .setNegativeButton("Later", null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LAN_REQUEST) {
            if (networkAllowed()) {
                companion.setEnabled(runtimePreferences.getBoolean("companion_enabled", false))
                navigate(destination)
            } else message("Local network access is off. You can enable it in Android Settings.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (authorization != null) closeAuthorization() else navigate(TouchDestination.HOME)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun fill() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    private companion object {
        const val PAPER = 0xFFF5F2E9.toInt()
        const val INK = 0xFF12242B.toInt()
        const val LAN_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LAN_REQUEST = 101
    }
}
