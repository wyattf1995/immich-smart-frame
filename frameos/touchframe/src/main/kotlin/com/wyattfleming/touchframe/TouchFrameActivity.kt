package com.wyattfleming.touchframe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.FrameLayout
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
import com.wyattfleming.touchframe.media.TouchPhotoOfDay
import com.wyattfleming.touchframe.ocean.*
import com.wyattfleming.touchframe.power.*
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
    private val worker = TouchTaskScope.newSingleWorker()
    // Ebbward is on the internet; its timeouts never queue Home Assistant loads behind them.
    private val oceanWorker = TouchTaskScope.newSingleWorker()
    // Power's first open reads up to 25 hour starts; it never queues Home or Calendar loads behind it.
    private val powerWorker = TouchTaskScope.newSingleWorker()
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
    private var calendarTask: java.util.concurrent.Future<*>? = null
    private val calendarRequestGeneration = java.util.concurrent.atomic.AtomicLong()
    private var calendarLastRefreshMillis = 0L
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
    private lateinit var ocean: TouchOceanView
    private var oceanState = TouchOceanState()
    private lateinit var power: TouchPowerView
    private var powerState = TouchPowerViewState(loading = true)
    private var powerRepository: TouchPowerRepository? = null
    private var powerInFlight = false
    private var todoRepository: TouchTodoRepository? = null
    private val todoPreferences by lazy { getSharedPreferences("touch_todo", MODE_PRIVATE) }
    private var todoBoard = TouchTodoBoard.notChosen()
    private var todoInFlight = false
    private var todoRefreshQueued = false
    private var photoDay: Long? = null
    private var photoInFlight = false
    private var oceanInFlight = false
    private val oceanRepository by lazy { TouchOceanRepository(java.io.File(filesDir, "touch-ocean/conditions.json")) }
    private var calendarState = TouchCalendarState(TouchCalendarWeekPresentation.DEFAULT_MODE, java.time.LocalDate.now(), java.time.LocalDate.now(), TouchCalendarRange.forMode(TouchCalendarWeekPresentation.DEFAULT_MODE, java.time.LocalDate.now()), emptyList(), TouchCalendarConnection.OFFLINE, null)
    private lateinit var settingsReturn: TouchSettingsReturn
    private var media: TouchMediaView? = null
    private var cameras: TouchCameraView? = null
    private var authorization: FrameOAuthWebSurface? = null
    private var authorizationContainer: View? = null
    private var pendingAuthorization: SharedPreferencesPendingOAuthStateStore? = null
    private var lanPromptShown = false
    private var lanDialog: AlertDialog? = null
    private val runtime get() = (application as TouchFrameApplication).runtime
    private val clock = object : Runnable {
        override fun run() {
            if (!resumed) return
            val now = System.currentTimeMillis()
            home.tick(now)
            updateDisplayBehavior(now)
            val isNight = TouchDayNight.isNight(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).hour)
            shell.setNight(isNight)
            shell.setClock(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(
                java.time.format.DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(this@TouchFrameActivity)) "H:mm" else "h:mm a", Locale.getDefault())))
            calendar.setNight(isNight)
            if (destination == TouchDestination.CALENDAR) calendar.tick(now)
            ocean.setNight(isNight)
            if (destination == TouchDestination.OCEAN) ocean.tick()
            power.setNight(isNight)
            if (destination == TouchDestination.POWER) power.tick()
            cameras?.setNight(isNight)
            main.postDelayed(this, 1_000L)
        }
    }
    private val refresh = Runnable {
        when (destination) {
            TouchDestination.HOME -> { refreshHome(); refreshHomeGlances() }
            TouchDestination.CALENDAR -> requestCalendar(calendarState.mode, calendarState.anchor, calendarState.selectedDate, true)
            TouchDestination.WEATHER -> refreshWeather()
            TouchDestination.OCEAN -> refreshOcean()
            TouchDestination.POWER -> refreshPower()
            else -> Unit
        }
    }

    private var unregisterPlatformBack: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            val dispatcher = onBackInvokedDispatcher
            val callback = android.window.OnBackInvokedCallback { handleBack() }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            unregisterPlatformBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
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
        shell = TouchShellLayout(this, { next ->
            navigate(next)
            if (next.isImmersiveMedia && destination == next) maybeShowEdgeHint()
        }) {
            if (!settingsReturn.open()) message("Turn on the FrameOS Touch return button before opening Android Settings.", TouchShellLayout.NoticeTone.ERROR)
        }
        home = TouchHomeView(this, { route ->
            TouchDestination.entries.firstOrNull { it.name.equals(route, true) }?.let { next ->
                // Home's Coming up always opens the current week, whatever week was browsed last.
                if (next == TouchDestination.CALENDAR) java.time.LocalDate.now().let { today ->
                    calendarState = calendarState.copy(anchor = today, selectedDate = today)
                }
                navigate(next)
            }
        }, ::signIn, { mode -> changeClimate(targetCommand = false) { it.changeClimateMode(mode) } },
            { value -> changeClimate(targetCommand = true) { it.changeClimateTarget(value) } }).apply {
            setWeatherLocation(configuration?.weatherLocationLabel)
            onRetry = ::refreshHome
            todoActions = object : TouchHomeTodoActions {
                override fun chooseLists() = discoverTodoLists()
                override fun saveLists(selection: TouchTodoSelection) = saveTodoSelection(selection)
                override fun complete(item: TouchTodoItem) = completeTodo(item)
                override fun add(list: TouchTodoListRef, summary: String) = addTodo(list, summary)
            }
        }
        weather = WeatherContentView(this).apply {
            locationLabel = configuration?.weatherLocationLabel
            presentation = WeatherPresentation(emptyMessage = "Sign in to Home Assistant for weather", authenticationRequired = true)
            setOnPrimaryActionListener(::signIn)
            visibility = View.GONE
        }
        calendar = TouchCalendarView(this, ::requestCalendar, ::signIn).apply { visibility = View.GONE }
        ocean = TouchOceanView(this) { refreshOcean(force = true) }.apply { visibility = View.GONE }
        power = TouchPowerView(this, ::refreshPower, ::signIn).apply { visibility = View.GONE }
        shell.content.addView(home, fill())
        shell.content.addView(weather, fill())
        shell.content.addView(calendar, fill())
        shell.content.addView(ocean, fill())
        shell.content.addView(power, fill())
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
        todoSelection().let { if (it.chosen) todoBoard = TouchTodoBoard(it, emptyMap(), TouchTodoState.LOADING, null) }
        home.renderTodo(todoBoard)
        calendar.render(calendarState)
        if (configuration == null) message("This frame needs its connection settings before Home, Calendar and Cameras can load.", TouchShellLayout.NoticeTone.ERROR)
        handleStartupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStartupIntent(intent)
    }

    /** Android's user-selected HOME entry point always resumes the ambient Photos view. */
    private fun handleStartupIntent(intent: Intent) {
        val startup = TouchStartupPolicy.destinationFor(intent.action, intent.categories ?: emptySet()) ?: return
        // A system HOME request ends a transient sign-in sheet, then follows normal navigation.
        closeAuthorization(restoreDestination = false)
        navigate(startup)
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
        todoRepository = TouchTodoRepository(config.homeAssistantOrigin, TouchHomeTokenProvider { deadline ->
            synchronized(tokenLock) { if (Thread.currentThread().isInterrupted) null else client.validAccessToken(deadline) }
        })
        powerRepository = TouchPowerRepository(config.homeAssistantOrigin, TouchHomeTokenProvider { deadline ->
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
        todoInFlight = false
        photoInFlight = false
        calendarTask?.cancel(true)
        calendarTask = null
        weatherInFlight = false
        oceanInFlight = false
        ocean.setActive(false)
        powerInFlight = false
        climateInFlight = false
        home.setClimateBusy(false)
        releaseData()
        media?.setVisibleActive(false)
        cameras?.setActive(false)
        closeAuthorization(restoreDestination = false)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterPlatformBack?.invoke()
        unregisterPlatformBack = null
        settingsReturn.remove()
        closeAuthorization(restoreDestination = false)
        tasks.close()
        companion.close()
        releaseData()
        media?.close()
        photoStore.close()
        cameras?.close()
        worker.shutdownNow()
        oceanWorker.shutdownNow()
        powerWorker.shutdownNow()
        authorizationWorker.shutdownNow()
        cancellationWorker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        shell.select(destination)
        home.refreshLayoutForConfiguration(newConfig)
    }

    private fun releaseData() {
        // Cancellation may disconnect a socket. Keep that work away from touch dispatch and
        // capture these exact instances so a resumed activity's new clients are unaffected.
        val oldRepository = repository
        val oldTodoRepository = todoRepository
        val oldPowerRepository = powerRepository
        val oldCalendarRepository = calendarRepository
        val oldWeather = weatherRemote
        val oldOAuth = oauth
        repository = null
        todoRepository = null
        powerRepository = null
        calendarRepository = null
        weatherRemote = null
        oauth = null
        cancellationWorker.execute {
            oldRepository?.close()
            oldTodoRepository?.cancel()
            oldPowerRepository?.cancel()
            oldCalendarRepository?.close()
            oldWeather?.cancel()
            oldOAuth?.cancelInFlight()
        }
    }

    private fun navigate(next: TouchDestination) {
        if (next.usesWebMedia && webMediaUrl(next) == null) {
            message("${next.label} isn\u2019t set up on this frame.", TouchShellLayout.NoticeTone.ERROR)
            return
        }
        if (next == TouchDestination.CAMERAS && configuration == null) { message("Cameras aren\u2019t set up on this frame.", TouchShellLayout.NoticeTone.ERROR); return }
        if ((next.usesWebMedia || next == TouchDestination.CAMERAS) && !networkAllowed()) {
            requestLocalNetwork()
            return
        }
        if (destination == TouchDestination.CALENDAR && next != TouchDestination.CALENDAR) {
            calendarRequestGeneration.incrementAndGet()
            calendarTask?.cancel(true)
            calendarTask = null
            calendarRepository?.invalidate()
        }
        destination = next
        shell.select(next)
        home.visibility = if (next == TouchDestination.HOME) View.VISIBLE else View.GONE
        weather.visibility = if (next == TouchDestination.WEATHER) View.VISIBLE else View.GONE
        calendar.visibility = if (next == TouchDestination.CALENDAR) View.VISIBLE else View.GONE
        ocean.visibility = if (next == TouchDestination.OCEAN) View.VISIBLE else View.GONE
        ocean.setActive(resumed && next == TouchDestination.OCEAN)
        power.visibility = if (next == TouchDestination.POWER) View.VISIBLE else View.GONE
        cameras?.visibility = if (next == TouchDestination.CAMERAS) View.VISIBLE else View.GONE
        cameras?.setActive(resumed && networkAllowed() && next == TouchDestination.CAMERAS)
        media?.setVisibleActive(resumed && networkAllowed() && next.usesWebMedia)
        main.removeCallbacks(refresh)
        if (next != TouchDestination.HOME) home.closeSheet()
        when (next) {
            TouchDestination.HOME -> { refreshHome(); refreshHomeGlances() }
            TouchDestination.CALENDAR -> requestCalendar(calendarState.mode, calendarState.anchor, calendarState.selectedDate, true)
            TouchDestination.WEATHER -> refreshWeather()
            TouchDestination.OCEAN -> refreshOcean()
            TouchDestination.POWER -> refreshPower()
            TouchDestination.PHOTOS, TouchDestination.ART, TouchDestination.BIRDS -> {
                val url = webMediaUrl(next) ?: run { message("${next.label} isn\u2019t set up on this frame.", TouchShellLayout.NoticeTone.ERROR); return }
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
                val view = cameras ?: TouchCameraView(this, config.homeAssistantOrigin, config.cameraEntityIds, { deadline ->
                    synchronized(tokenLock) {
                        if (Thread.currentThread().isInterrupted) null else oauth?.validAccessToken(deadline)
                    }
                }, runtime).also {
                    cameras = it
                    it.onSignIn = ::signIn
                    shell.content.addView(it, fill())
                }
                view.visibility = View.VISIBLE
                view.setNight(TouchDayNight.isNight(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).hour))
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
                model = cached.copy(connection = TouchConnection.REFRESHING)
                home.render(model)
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

    private fun requestCalendar(mode: TouchCalendarMode, anchor: java.time.LocalDate, selected: java.time.LocalDate, forceRefresh: Boolean) {
        val source = calendarRepository ?: return
        if (!resumed || !networkAllowed()) return
        val range = TouchCalendarRange.forMode(mode, anchor)
        val selectedForRange = TouchCalendarRenderPlan.selectedFor(range, selected, anchor)
        val sameRange = calendarState.range == range
        if (sameRange && !forceRefresh) {
            calendarState = calendarState.copy(mode = mode, anchor = anchor, selectedDate = selectedForRange)
            calendar.render(calendarState)
            return
        }
        val now = System.currentTimeMillis()
        if (sameRange && calendarState.updatedEpochMillis != null && now - calendarLastRefreshMillis < 60_000L) {
            calendarState = calendarState.copy(mode = mode, anchor = anchor, selectedDate = selectedForRange)
            calendar.render(calendarState)
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, (60_000L - (now - calendarLastRefreshMillis)).coerceAtLeast(1_000L))
            return
        }
        val prior = calendarState
        val request = calendarRequestGeneration.incrementAndGet()
        calendarTask?.cancel(true)
        source.invalidate()
        calendarState = calendarState.copy(mode = mode, anchor = anchor, selectedDate = selectedForRange, range = range, events = if (sameRange) calendarState.events else emptyList(), loading = true, partial = false)
        calendar.render(calendarState)
        calendarTask = tasks.submit(worker) { isCurrent ->
            // The atomic request epoch is the only cross-thread request gate; destination stays main-owned.
            if (!isCurrent() || request != calendarRequestGeneration.get()) return@submit
            val loaded = runCatching { source.load(range) }.getOrElse { TouchCalendarLoad(emptyList(), TouchCalendarConnection.OFFLINE) }
            if (!isCurrent() || request != calendarRequestGeneration.get()) return@submit
            main.post {
                if (!isCurrent() || request != calendarRequestGeneration.get() || destination != TouchDestination.CALENDAR) return@post
                calendarTask = null
                val retained = if (loaded.connection == TouchCalendarConnection.OFFLINE && prior.range == range) prior.events else loaded.events
                calendarState = calendarState.copy(events = retained, connection = loaded.connection, updatedEpochMillis = loaded.updatedEpochMillis ?: if (prior.range == range) prior.updatedEpochMillis else null, loading = false, partial = loaded.partial)
                calendarLastRefreshMillis = System.currentTimeMillis()
                calendar.render(calendarState)
                main.removeCallbacks(refresh)
                main.postDelayed(refresh, 60_000L)
            }
        }
    }

    private fun changeClimate(targetCommand: Boolean, command: (TouchHomeRepository) -> Boolean) {
        val source = repository ?: return
        if (!resumed || climateInFlight || !networkAllowed()) return
        climateInFlight = true
        home.setClimateBusy(true)
        val previous = model
        tasks.submit(worker) { isCurrent ->
            if (!isCurrent()) return@submit
            val accepted = runCatching { command(source) }.getOrDefault(false)
            val next = if (accepted) runCatching { source.refresh(previous) }.getOrNull() else null
            main.post {
                if (!isCurrent()) return@post
                climateInFlight = false
                if (next != null) { model = next; home.render(next) }
                val confirmed = accepted && next?.connection == TouchConnection.CONNECTED
                // A mode change never discards a pending setpoint draft; only a target command settles it.
                if (targetCommand || !confirmed) home.onClimateCommandFinished(confirmed) else home.setClimateBusy(false)
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

    /** Public Ebbward conditions; no Home Assistant sign-in. Fresh data is reused for ten minutes. */
    private fun refreshOcean(force: Boolean = false) {
        if (!resumed || oceanInFlight) return
        val now = System.currentTimeMillis()
        val fresh = oceanState.connection == TouchOceanConnection.LIVE &&
            oceanState.fetchedAtMillis?.let { now - it in 0 until OCEAN_REFRESH_MILLIS } == true
        if (fresh && !force) {
            ocean.render(oceanState)
            showOceanGlance()
            scheduleOceanRefresh(OCEAN_REFRESH_MILLIS - (now - (oceanState.fetchedAtMillis ?: now)))
            return
        }
        oceanInFlight = true
        oceanState = oceanState.copy(loading = true)
        ocean.render(oceanState)
        showOceanGlance()
        tasks.submit(oceanWorker) { isCurrent ->
            if (!isCurrent()) return@submit
            val load = runCatching { oceanRepository.load(TouchOceanRepository.DEFAULT_SPOT, OCEAN_TIMEOUT_MILLIS) }
                .getOrElse { TouchOceanLoad(null, TouchOceanConnection.OFFLINE) }
            main.post {
                if (!isCurrent()) return@post
                oceanInFlight = false
                val live = load.connection == TouchOceanConnection.LIVE
                oceanState = TouchOceanState(
                    conditions = load.conditions ?: oceanState.conditions,
                    connection = load.connection,
                    loading = false,
                    fetchedAtMillis = if (live) System.currentTimeMillis() else oceanState.fetchedAtMillis,
                )
                ocean.render(oceanState)
                showOceanGlance()
                // Offline: try again sooner, the way Weather does.
                scheduleOceanRefresh(if (live) OCEAN_REFRESH_MILLIS else 60_000L)
            }
        }
    }

    private fun scheduleOceanRefresh(delayMillis: Long) {
        if (destination != TouchDestination.OCEAN) return
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, delayMillis.coerceAtLeast(1_000L))
    }

    /**
     * Power's readings from Home Assistant, once a minute while Power is shown. A failed read keeps the last snapshot
     * so the page can say how old it is; sign-in and a missing Power package replace it with their own screens.
     */
    private fun refreshPower() {
        if (!resumed || powerInFlight) return
        val source = powerRepository
        if (source == null || !networkAllowed()) {
            powerState = powerState.copy(loading = false, connection = if (configuration == null) TouchPowerConnection.NOT_SET_UP else TouchPowerConnection.OFFLINE)
            power.render(powerState)
            schedulePowerRefresh(POWER_RETRY_MILLIS)
            return
        }
        powerInFlight = true
        if (powerState.snapshot == null) {
            powerState = powerState.copy(loading = true)
            power.render(powerState)
        }
        tasks.submit(powerWorker) { isCurrent ->
            if (!isCurrent()) return@submit
            val load = runCatching { source.load(System.currentTimeMillis()) }
                .getOrElse { TouchPowerLoad(null, TouchPowerConnection.OFFLINE) }
            main.post {
                if (!isCurrent()) return@post
                powerInFlight = false
                powerState = TouchPowerViewState(
                    snapshot = load.snapshot ?: powerState.snapshot.takeIf { load.connection == TouchPowerConnection.OFFLINE },
                    connection = load.connection,
                    loading = false,
                )
                power.render(powerState)
                schedulePowerRefresh(if (load.connection == TouchPowerConnection.LIVE) POWER_REFRESH_MILLIS else POWER_RETRY_MILLIS)
            }
        }
    }

    private fun schedulePowerRefresh(delayMillis: Long) {
        if (destination != TouchDestination.POWER) return
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, delayMillis.coerceAtLeast(1_000L))
    }

    /** Home's other inputs: the chosen to-do lists, the day's photo and the ocean glance (fresh for ten minutes). */
    private fun refreshHomeGlances() {
        refreshTodo()
        loadPhotoOfDay()
        refreshOcean()
    }

    private fun todoSelection(): TouchTodoSelection = TouchTodoSelection.decode(todoPreferences.getString(TODO_SELECTION, null))

    private fun refreshTodo() {
        val source = todoRepository ?: return
        if (!resumed || !networkAllowed()) return
        if (todoInFlight) { todoRefreshQueued = true; return }
        val selection = todoSelection()
        if (!selection.chosen) {
            todoBoard = TouchTodoBoard.notChosen()
            home.renderTodo(todoBoard)
            return
        }
        if (todoBoard.selection != selection) {
            todoBoard = TouchTodoBoard(selection, emptyMap(), TouchTodoState.LOADING, null)
            home.renderTodo(todoBoard)
        }
        todoInFlight = true
        todoRefreshQueued = false
        tasks.submit(worker) { isCurrent ->
            if (!isCurrent()) return@submit
            val load = runCatching { source.load(selection) }.getOrElse { TouchTodoLoad(emptyMap(), TouchTodoState.OFFLINE) }
            main.post {
                if (!isCurrent()) return@post
                todoInFlight = false
                if (todoSelection() == selection) {
                    todoBoard = when (load.state) {
                        TouchTodoState.READY -> TouchTodoBoard(selection, load.items, TouchTodoState.READY, System.currentTimeMillis())
                        // Offline keeps the last lists on screen; the column says they may be stale.
                        TouchTodoState.OFFLINE -> todoBoard.copy(state = TouchTodoState.OFFLINE)
                        else -> TouchTodoBoard(selection, emptyMap(), load.state, null)
                    }
                    home.renderTodo(todoBoard)
                }
                if (todoRefreshQueued || todoSelection() != selection) refreshTodo()
            }
        }
    }

    private fun discoverTodoLists() {
        val source = todoRepository ?: return
        if (!resumed) return
        if (!networkAllowed()) { requestLocalNetwork(); return }
        home.showListPicker(null)
        tasks.submit(worker) { isCurrent ->
            if (!isCurrent()) return@submit
            val found = runCatching { source.discover() }.getOrElse { TouchTodoDiscovery(emptyList(), TouchTodoState.OFFLINE) }
            main.post { if (isCurrent()) home.updateListPicker(found) }
        }
    }

    private fun saveTodoSelection(selection: TouchTodoSelection) {
        todoPreferences.edit().putString(TODO_SELECTION, selection.encode()).apply()
        todoBoard = if (selection.chosen) TouchTodoBoard(selection, emptyMap(), TouchTodoState.LOADING, null) else TouchTodoBoard.notChosen()
        home.renderTodo(todoBoard)
        refreshTodo()
    }

    private fun completeTodo(item: TouchTodoItem) {
        val source = todoRepository
        if (source == null || !resumed || !networkAllowed()) {
            home.onTodoCompleteFailed(item)
            return
        }
        tasks.submit(worker) { isCurrent ->
            val done = isCurrent() && runCatching { source.complete(item) }.getOrDefault(false)
            main.post {
                if (!isCurrent()) return@post
                if (done) refreshTodo()
                else {
                    home.onTodoCompleteFailed(item)
                    message("Couldn\u2019t mark \u201c${item.summary}\u201d done. Home Assistant didn\u2019t confirm it.", TouchShellLayout.NoticeTone.ERROR)
                }
            }
        }
    }

    private fun addTodo(list: TouchTodoListRef, summary: String) {
        val source = todoRepository ?: return
        if (!resumed || !networkAllowed()) return
        tasks.submit(worker) { isCurrent ->
            val added = isCurrent() && runCatching { source.add(list.entityId, summary) }.getOrDefault(false)
            main.post {
                if (!isCurrent()) return@post
                if (added) {
                    message("Added to ${list.name}")
                    refreshTodo()
                } else {
                    message("Couldn\u2019t add \u201c$summary\u201d. Check the frame\u2019s connection.", TouchShellLayout.NoticeTone.ERROR, "Try again") { addTodo(list, summary) }
                }
            }
        }
    }

    /** One photo a day from the slideshow's saved JPEGs, decoded off the main thread at panel size. */
    private fun loadPhotoOfDay() {
        if (!resumed || photoInFlight) return
        val day = java.time.LocalDate.now().toEpochDay()
        if (photoDay == day) return
        val measured = home.photoTargetSize()
        val wall = TouchScale.forScreen(resources.configuration) == TouchScale.WALL
        val (width, height) = if (measured.first > 0 && measured.second > 0) measured else if (wall) 700 to 1000 else 392 to 732
        photoInFlight = true
        tasks.submit(worker) { isCurrent ->
            val bitmap = if (!isCurrent()) null else runCatching {
                val entries = photoStore.reserve.entries()
                TouchPhotoOfDay.pick(entries.map { it.assetId }, day)
                    ?.let { id -> entries.firstOrNull { it.assetId == id } }
                    ?.let { TouchPhotoOfDay.decode(it.file, width, height) }
            }.getOrNull()
            main.post {
                photoInFlight = false
                if (!isCurrent()) { bitmap?.recycle(); return@post }
                // No saved photos yet: the panel stays plain and the next refresh tries again.
                if (bitmap != null) {
                    photoDay = day
                    home.setPhoto(bitmap)
                }
            }
        }
    }

    private fun showOceanGlance() {
        val glance = oceanState.conditions?.let {
            TouchOceanGlance.from(it, System.currentTimeMillis(), ZoneId.systemDefault(), android.text.format.DateFormat.is24HourFormat(this))
        }
        home.setOcean(glance, oceanInFlight)
    }

    /** The photo is the one large bitmap Home holds; it goes first when Android asks for memory back. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && destination != TouchDestination.HOME) {
            home.setPhoto(null)
            photoDay = null
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
        val palette = TouchPalette.forNight(TouchDayNight.isNight(Instant.now().atZone(ZoneId.systemDefault()).hour))
        val kit = TouchKit(this, palette, TouchScale.forScreen(resources.configuration))
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(palette.wall)
        }
        val surface = FrameOAuthWebSurface(this, runtime, request.url, config.homeAssistantUrl, callback, { url ->
            val code = verifier.verify(url)
            closeAuthorization(restoreDestination = false)
            if (code == null) {
                message("Sign-in couldn\u2019t be verified.", TouchShellLayout.NoticeTone.ERROR, "Try again", ::signIn)
                navigate(destination)
            }
            else {
                tasks.submit(authorizationWorker) { isCurrent ->
                    val deadline = WeatherRequestDeadline(timeoutMillis = 12_000L)
                    val success = synchronized(tokenLock) { isCurrent() && client.exchangeAuthorizationCode(code.code, deadline) }
                    main.post {
                        if (!isCurrent()) return@post
                        if (success) message("Connected to Home Assistant")
                        else message("Sign-in couldn\u2019t be completed.", TouchShellLayout.NoticeTone.ERROR, "Try again", ::signIn)
                        navigate(destination)
                    }
                }
            }
        }, { closeAuthorization(); message("Sign-in couldn\u2019t be completed.", TouchShellLayout.NoticeTone.ERROR, "Try again", ::signIn) })
        container.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.moulding)
            setPadding(kit.dp(32), 0, kit.dp(12), 0)
            addView(android.widget.LinearLayout(this@TouchFrameActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(kit.text(getString(R.string.touch_sign_in_title), kit.scale.headline, TouchFonts.Face.DISPLAY_BOLD, palette.mouldingInk))
                addView(kit.text(getString(R.string.touch_sign_in_subtitle), kit.scale.caption, color = palette.mouldingMuted))
            }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            val barHeight = kit.dp(TouchShellLayout.barHeightDpFor(resources.configuration.screenWidthDp))
            addView(kit.button(getString(R.string.touch_cancel_sign_in), TouchKit.Emphasis.QUIET, quietInk = palette.mouldingInk) {
                pending.consume()
                closeAuthorization()
            }.apply { minimumHeight = 0 }, android.widget.LinearLayout.LayoutParams(-2, barHeight - kit.dp(12)))
        }, android.widget.LinearLayout.LayoutParams(-1, kit.dp(TouchShellLayout.barHeightDpFor(resources.configuration.screenWidthDp))))
        container.addView(surface, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        authorization = surface
        authorizationContainer = container
        media?.setVisibleActive(false)
        cameras?.setActive(false)
        root.addView(container, fill())
        runCatching { surface.start() }.onFailure { closeAuthorization(); message("Sign-in isn\u2019t available right now.", TouchShellLayout.NoticeTone.ERROR) }
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
        if (TouchIdleReturnPolicy.shouldReturnToPhotos(destination, authorization != null, configuration != null, networkAllowed(),
                SystemClock.elapsedRealtime() - lastInteractionMillis, experience.idleReturnSeconds)) {
            // After an idle return, Calendar opens on today rather than whatever week was browsed last.
            java.time.LocalDate.now().let { today -> calendarState = calendarState.copy(anchor = today, selectedDate = today) }
            lastInteractionMillis = SystemClock.elapsedRealtime()
            navigate(TouchDestination.PHOTOS)
        }
    }

    private fun photoProfile(config: TouchConfiguration): String = FramePhotosProfileUrl.profile(config.photosUrl) ?: "balanced"

    private fun companionStatus(): FrameRemoteStatus? {
        if (!resumed) return null
        val reserve = photoStore.publishedStatus()
        return FrameRemoteStatus(
            // The companion protocol has no Ocean mode yet; Ocean reports as the other conditions screen.
            mode = FrameMode.entries.firstOrNull { it.name == destination.name } ?: FrameMode.WEATHER, photosPaused = photosPaused,
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
        val destinations = TouchDestinationOrder.withLocal(TouchDestinationOrder.available(requested, birdsConfigured))
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
        if (Build.VERSION.SDK_INT < 37 || networkAllowed() || lanDialog?.isShowing == true) return
        lanPromptShown = true
        lanDialog = AlertDialog.Builder(this).setTitle("Connect to your home")
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
            } else message("Local network access is off. Turn it on in Android Settings to load Home, Calendar and Cameras.", TouchShellLayout.NoticeTone.ERROR)
        }
    }

    // API33+ gestures use the lifecycle-owned platform callback registered above.
    // The legacy override remains necessary for these API23/API27 physical frames.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (authorization != null) closeAuthorization()
        else if (destination == TouchDestination.HOME && home.closeSheet()) Unit
        else navigate(TouchDestination.HOME)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun message(
        value: String,
        tone: TouchShellLayout.NoticeTone = TouchShellLayout.NoticeTone.INFO,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) = shell.showNotice(value, tone, actionLabel, action)

    /** Shown after a tap into Photos or Art, for the first few times on this frame. */
    private fun maybeShowEdgeHint() {
        val shown = runtimePreferences.getInt(EDGE_HINT_COUNT, 0)
        if (shown >= EDGE_HINT_LIMIT) return
        runtimePreferences.edit().putInt(EDGE_HINT_COUNT, shown + 1).apply()
        shell.showEdgeHint()
    }
    private fun fill() = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    private companion object {
        const val EDGE_HINT_COUNT = "edge_hint_count"
        const val TODO_SELECTION = "selection"
        const val OCEAN_REFRESH_MILLIS = 10 * 60_000L
        const val OCEAN_TIMEOUT_MILLIS = 15_000
        const val POWER_REFRESH_MILLIS = 60_000L
        const val POWER_RETRY_MILLIS = 30_000L
        const val EDGE_HINT_LIMIT = 3
        const val LAN_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val LAN_REQUEST = 101
    }
}
