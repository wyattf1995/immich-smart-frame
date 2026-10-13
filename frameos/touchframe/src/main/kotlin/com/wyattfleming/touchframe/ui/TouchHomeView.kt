package com.wyattfleming.touchframe.ui

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wyattfleming.touchframe.R
import com.wyattfleming.touchframe.home.TouchClimate
import com.wyattfleming.touchframe.home.TouchClimateDraft
import com.wyattfleming.touchframe.home.TouchConnection
import com.wyattfleming.touchframe.home.TouchHomeAgenda
import com.wyattfleming.touchframe.home.TouchHomeModel
import com.wyattfleming.touchframe.home.TouchWeatherLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Native, scrollable Home surface. The activity supplies live data on its
 * background refresh path; this view does not make Home Assistant or web calls.
 *
 * Structure is built once per layout class/day-night style; data updates refill only the
 * slot whose inputs changed, so a background refresh never interrupts a press on another panel.
 */
class TouchHomeView private constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    private val onNavigate: (String) -> Unit,
    private val onSignIn: () -> Unit,
    private val onClimateMode: (String) -> Unit,
    private val onClimateTarget: (Double) -> Unit,
) : FrameLayout(context, attrs, defStyleAttr) {
    constructor(context: Context) : this(context, null, 0, {}, {}, {}, {})

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0, {}, {}, {}, {})

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, {}, {}, {}, {})

    constructor(
        context: Context,
        onNavigate: (String) -> Unit,
        onSignIn: () -> Unit,
        onClimateMode: (String) -> Unit,
        onClimateTarget: (Double) -> Unit,
    ) : this(context, null, 0, onNavigate, onSignIn, onClimateMode, onClimateTarget)

    /** Retries the Home refresh from the offline banner. */
    var onRetry: (() -> Unit)? = null

    private var model: TouchHomeModel? = null
    private var destinations: List<TouchDestination> = TouchDestinationOrder.defaults
    private var nowMillis: Long = System.currentTimeMillis()
    private var night = TouchDayNight.isNight(hourAt(nowMillis))
    private var scale = TouchScale.PANEL
    private var locationLabel: String? = null
    private val draft = TouchClimateDraft()
    private var climateBusy = false
    private var climateResult: ClimateResult? = null
    private var clockView: TextView? = null
    private var suffixView: TextView? = null
    private var dateView: TextView? = null
    private var glanceSlot: FrameLayout? = null
    private var bannerSlot: FrameLayout? = null
    private var comingSlot: FrameLayout? = null
    private var thermoSlot: FrameLayout? = null
    private var statusSlot: FrameLayout? = null
    private var renderedKeys = HashMap<String, Any?>()
    private var renderedDay: java.time.LocalDate? = null
    private var scrollView: ScrollView? = null
    private var scrollPosition = 0
    private val layoutTransition = TouchHomeLayoutTransition()
    private var layoutRebuildPosted = false

    private enum class ClimateResult { CONFIRMED, FAILED }

    init {
        isFocusable = true
        rebuild()
    }

    /** Remote mode order decides which panels may open their destination; Home remains first. */
    fun setDestinations(remoteOrder: List<TouchDestination>) {
        val normalized = TouchDestinationOrder.normalize(remoteOrder)
        if (destinations == normalized) return
        destinations = normalized
        refillSlots(force = true)
    }

    /** Optional configured place name shown under the weather glance (display text only). */
    fun setWeatherLocation(label: String?) {
        if (locationLabel == label) return
        locationLabel = label
        refillSlots()
    }

    /** Renders the newest caller-owned snapshot without clearing an active draft. */
    fun render(model: TouchHomeModel) {
        if (this.model == model) return
        val previousEntity = this.model?.climate?.entityId
        this.model = model
        draft.onRefreshed(previousEntity, model.climate)
        refillSlots()
    }

    /** Lets the activity reflect a real command in flight without inventing an outcome. */
    fun setClimateBusy(busy: Boolean) {
        if (climateBusy == busy) return
        climateBusy = busy
        if (busy) climateResult = null
        refillSlots()
    }

    /** A confirmed command returns Home to Home Assistant's value; a failure keeps the draft to retry. */
    fun onClimateCommandFinished(confirmed: Boolean) {
        climateBusy = false
        climateResult = if (confirmed) ClimateResult.CONFIRMED else ClimateResult.FAILED
        draft.onCommandFinished(confirmed, model?.climate)
        removeCallbacks(revertDraft)
        removeCallbacks(settleResult)
        if (confirmed) postDelayed(settleResult, CONFIRMATION_MILLIS)
        refillSlots()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(revertDraft)
        removeCallbacks(settleResult)
        super.onDetachedFromWindow()
    }

    /** Updates the retained clock nodes; only a day/night transition rebuilds the structure. */
    fun tick(nowMillis: Long) {
        this.nowMillis = nowMillis
        val nextNight = TouchDayNight.isNight(hourAt(nowMillis))
        if (nextNight != night) {
            night = nextNight
            rebuild()
        } else {
            clockView?.text = timeText()
            suffixView?.text = suffixText()
            dateView?.text = dateText()
            if (dayAt(nowMillis) != renderedDay) refillSlots()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        requestLayoutClass(isLargeLandscape())
        val nextScale = TouchScale.forContentHeightDp(height / resources.displayMetrics.density)
        if (height > 0 && nextScale != scale) post { if (TouchScale.forContentHeightDp(this.height / resources.displayMetrics.density) != scale) rebuild() }
    }

    /** Defers a consumed Activity configuration change until the content has a layout pass. */
    fun refreshLayoutForConfiguration(configuration: Configuration) {
        val contentWidthDp = TouchHomeLayout.contentWidthDp(
            configuration.screenWidthDp.toFloat(),
            TouchShellLayout.railWidthDpFor(configuration.screenWidthDp).toFloat(),
        )
        requestLayoutClass(TouchHomeLayout.isLargeLandscape(contentWidthDp, configuration.screenHeightDp.toFloat()))
        requestLayout()
    }

    private fun requestLayoutClass(large: Boolean) {
        if (!layoutTransition.request(large) || layoutRebuildPosted) return
        layoutRebuildPosted = true
        post {
            layoutRebuildPosted = false
            layoutTransition.consume()?.let(::rebuild)
        }
    }

    private fun kit() = TouchKit(context, TouchPalette.forNight(night), scale)

    private fun rebuild(large: Boolean = isLargeLandscape()) {
        layoutTransition.markRendered(large)
        scale = TouchScale.forContentHeightDp(if (height > 0) height / resources.displayMetrics.density else resources.configuration.screenHeightDp.toFloat())
        scrollPosition = scrollView?.scrollY ?: scrollPosition
        removeAllViews()
        renderedKeys = HashMap()
        val kit = kit()
        setBackgroundColor(kit.palette.wall)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        scrollView = scroll
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = kit.dp(scale.pad)
            setPadding(pad, (pad * 0.8f).toInt(), pad, (pad * 0.7f).toInt())
        }
        content.addView(header(kit, large), LinearLayout.LayoutParams(MATCH, WRAP))
        bannerSlot = FrameLayout(context).also { content.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        val main = LinearLayout(context).apply { orientation = if (large) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val coming = FrameLayout(context).apply {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) post { refillSlots() }
            }
        }
        val thermo = FrameLayout(context)
        comingSlot = coming
        thermoSlot = thermo
        if (large) {
            main.addView(coming, LinearLayout.LayoutParams(0, MATCH, 1.2f).apply { marginEnd = kit.dp(scale.gap) })
            main.addView(thermo, LinearLayout.LayoutParams(0, MATCH, 1f))
            content.addView(main, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(scale.gap) })
        } else {
            main.addView(coming, LinearLayout.LayoutParams(MATCH, WRAP))
            main.addView(thermo, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(scale.gap) })
            content.addView(main, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(scale.gap) })
        }
        statusSlot = FrameLayout(context).also { content.addView(it, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(14) }) }
        scroll.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refillSlots(force = true)
        scroll.post { scroll.scrollTo(0, scrollPosition.coerceAtLeast(0)) }
    }

    private fun header(kit: TouchKit, large: Boolean): View = LinearLayout(context).apply {
        orientation = if (large) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val clock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val time = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = true
                val clockText = kit.text(timeText(), scale.clock, TouchFonts.Face.DISPLAY).apply { letterSpacing = -0.02f }
                val suffix = kit.text(suffixText(), scale.clockSuffix, TouchFonts.Face.TEXT_BOLD, kit.palette.inkMuted)
                clockView = clockText
                suffixView = suffix
                addView(clockText)
                addView(suffix, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(14) })
            }
            addView(time)
            val date = kit.text(dateText(), scale.headline, TouchFonts.Face.TEXT_MEDIUM, kit.palette.inkMuted)
            dateView = date
            addView(date, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) })
        }
        val glance = FrameLayout(context)
        glanceSlot = glance
        if (large) {
            addView(clock, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = kit.dp(scale.gap) })
            addView(glance, LinearLayout.LayoutParams(WRAP, WRAP))
        } else {
            addView(clock)
            addView(glance, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(16) })
        }
    }

    /** Refills only the slots whose visible inputs changed. */
    private fun refillSlots(force: Boolean = false) {
        val kit = kit()
        val snapshot = model
        val connection = snapshot?.connection
        val today = dayAt(nowMillis)
        renderedDay = today
        val signIn = connection == TouchConnection.SIGN_IN_REQUIRED
        val lines = comingLineBudget(kit, connection == TouchConnection.OFFLINE)
        fill("glance", listOf(snapshot?.weatherText, snapshot?.temperatureText, connection, snapshot?.lastUpdatedEpochMillis, locationLabel, destinations), force, glanceSlot) { glance(kit, snapshot) }
        fill("banner", listOf(connection, snapshot?.lastUpdatedEpochMillis), force, bannerSlot) { banner(kit, snapshot) }
        fill("coming", listOf(snapshot?.events, connection, today, destinations, lines), force, comingSlot) {
            if (signIn) connectPanel(kit) else coming(kit, snapshot, lines)
        }
        fill("thermo", listOf(snapshot?.climate, connection, climateBusy, climateResult, draft.target, draft.dirty), force, thermoSlot) {
            if (signIn) null else thermostat(kit, snapshot)
        }
        thermoSlot?.visibility = if (signIn) View.GONE else View.VISIBLE
        (comingSlot?.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            val weight = if (signIn) 2.2f else 1.2f
            val margin = if (signIn) 0 else kit.dp(scale.gap)
            if (params.weight != weight && params.width == 0) { params.weight = weight; comingSlot?.requestLayout() }
            if (params.marginEnd != margin && params.width == 0) { params.marginEnd = margin; comingSlot?.requestLayout() }
        }
        fill("status", listOf(connection, snapshot?.lastUpdatedEpochMillis), force, statusSlot) { status(kit, snapshot) }
    }

    /** Lines that fit in the Coming up panel; before the first layout pass, a conservative default. */
    private fun comingLineBudget(kit: TouchKit, banner: Boolean): Int {
        val slotHeight = comingSlot?.height ?: 0
        val large = isLargeLandscape()
        if (slotHeight <= 0 || !large) return (if (scale == TouchScale.WALL) 13 else 9) - if (banner) 2 else 0
        val padding = kit.dp((scale.pad * 0.6f).toInt()) * 2
        val header = kit.dp((scale.headline * 1.3f).toInt())
        val line = kit.dp((scale.body * 1.25f + 14).toInt())
        return ((slotHeight - padding - header) / line).coerceAtLeast(2)
    }

    private fun fill(key: String, inputs: Any?, force: Boolean, slot: FrameLayout?, build: () -> View?) {
        slot ?: return
        if (!force && renderedKeys.containsKey(key) && renderedKeys[key] == inputs) return
        renderedKeys[key] = inputs
        slot.removeAllViews()
        build()?.let { slot.addView(it, FrameLayout.LayoutParams(MATCH, if (slot === comingSlot || slot === thermoSlot) MATCH else WRAP)) }
    }

    private fun glance(kit: TouchKit, snapshot: TouchHomeModel?): View {
        val p = kit.palette
        val weatherState = snapshot?.weatherText.orEmpty()
        val condition = TouchWeatherLabel.condition(weatherState)
        val temperature = snapshot?.temperatureText?.takeIf { it.isNotBlank() && !it.equals("Unavailable", true) }
        val canOpen = TouchDestination.WEATHER in destinations
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val base = kit.rounded(p.mat, scale.radius, p.line)
            background = if (canOpen) kit.pressable(base, scale.radius) else base
            setPadding(kit.dp(26), kit.dp(18), kit.dp(22), kit.dp(18))
            val iconSize = (scale.display * 0.9f).toInt()
            val signIn = snapshot?.connection == TouchConnection.SIGN_IN_REQUIRED && condition == null
            if (condition == null) {
                addView(kit.icon(R.drawable.ic_touch_weather, p.inkMuted, iconSize))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text(if (signIn) "Weather needs sign-in" else "Weather unavailable", scale.body, TouchFonts.Face.TEXT_BOLD))
                    locationLabel?.let { addView(kit.text(it, scale.caption, color = p.inkMuted)) }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(20) })
            } else {
                addView(kit.icon(glyphIcon(weatherState), p.ink, iconSize))
                temperature?.let {
                    addView(kit.text(it, scale.display, TouchFonts.Face.DISPLAY_BOLD), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(20) })
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(kit.text(condition, scale.body, TouchFonts.Face.TEXT_BOLD))
                    val caption = if (snapshot?.connection == TouchConnection.OFFLINE) snapshot.lastUpdatedEpochMillis?.let { "As of ${timeAt(it)}" } else locationLabel
                    caption?.let { addView(kit.text(it, scale.caption, color = p.inkMuted), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(4) }) }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(22) })
            }
            if (canOpen) {
                addView(kit.icon(R.drawable.ic_touch_chevron_right, p.inkMuted, 28), LinearLayout.LayoutParams(kit.dp(28), kit.dp(28)).apply { marginStart = kit.dp(18) })
                isClickable = true
                isFocusable = true
                contentDescription = listOfNotNull("Weather", temperature, condition, locationLabel, "open Weather").joinToString(", ")
                setOnClickListener { onNavigate(TouchDestination.WEATHER.name.lowercase(Locale.ROOT)) }
            }
        }
    }

    private fun banner(kit: TouchKit, snapshot: TouchHomeModel?): View? {
        if (snapshot?.connection != TouchConnection.OFFLINE) return null
        val p = kit.palette
        val message = snapshot?.lastUpdatedEpochMillis?.let { "Can\u2019t reach Home Assistant. Showing what was saved at ${timeAt(it)}." }
            ?: "Can\u2019t reach Home Assistant. Check the frame\u2019s Wi\u2011Fi, then try again."
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = kit.rounded(p.pending, scale.radius)
            minimumHeight = kit.dp(scale.target + 20)
            setPadding(kit.dp(24), kit.dp(10), kit.dp(10), kit.dp(10))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(kit.icon(R.drawable.ic_touch_offline, p.pendingInk, 32))
            addView(kit.text(message, scale.body, TouchFonts.Face.TEXT_MEDIUM, p.pendingInk), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = kit.dp(18)
                marginEnd = kit.dp(18)
            })
            addView(kit.button("Try again", TouchKit.Emphasis.QUIET, quietInk = p.pendingInk) { onRetry?.invoke() })
        }
        return FrameLayout(context).apply {
            setPadding(0, kit.dp(scale.gap), 0, 0)
            addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    /** Signed out: one clear call to action instead of two empty panels. Photos and Art don't need it. */
    private fun connectPanel(kit: TouchKit): View = kit.panel().apply {
        gravity = Gravity.CENTER
        val p = kit.palette
        addView(kit.icon(R.drawable.ic_touch_user, p.inkMuted, 64))
        addView(kit.text("Connect this frame to Home Assistant", scale.headline, TouchFonts.Face.DISPLAY_BOLD).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(16) })
        addView(kit.text("Sign in once to show your calendar, thermostat and weather here. Photos and Art keep working without it.",
            scale.body, color = p.inkMuted).apply {
            gravity = Gravity.CENTER
            maxWidth = kit.dp(680)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(12) })
        addView(kit.button("Sign in", TouchKit.Emphasis.PRIMARY) { onSignIn() }, LinearLayout.LayoutParams(WRAP, kit.dp(scale.target)).apply { topMargin = kit.dp(20) })
    }

    private fun coming(kit: TouchKit, snapshot: TouchHomeModel?, maxLines: Int): View {
        val p = kit.palette
        val canOpen = TouchDestination.CALENDAR in destinations
        return kit.panel().apply {
            if (canOpen) {
                background = kit.pressable(kit.rounded(p.mat, scale.radius, p.line), scale.radius)
                isClickable = true
                isFocusable = true
                contentDescription = "Coming up, open Calendar"
                setOnClickListener { onNavigate(TouchDestination.CALENDAR.name.lowercase(Locale.ROOT)) }
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(kit.text("Coming up", scale.headline, TouchFonts.Face.DISPLAY_BOLD), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (canOpen) {
                    addView(kit.text("Calendar", scale.label, TouchFonts.Face.TEXT_BOLD, p.accentInk))
                    addView(kit.icon(R.drawable.ic_touch_chevron_right, p.accentInk, 26), LinearLayout.LayoutParams(kit.dp(26), kit.dp(26)).apply { marginStart = kit.dp(4) })
                }
            })
            val events = snapshot?.events.orEmpty()
            var overflow = 0
            when {
                snapshot == null || (snapshot.connection == TouchConnection.REFRESHING && snapshot.lastUpdatedEpochMillis == null) ->
                    skeletonRows(kit, 4).forEach { addView(it) }
                events.isEmpty() && snapshot.connection == TouchConnection.SIGN_IN_REQUIRED ->
                    addView(note(kit, "Your next events appear here after you sign in."))
                events.isEmpty() && snapshot.connection == TouchConnection.OFFLINE ->
                    addView(note(kit, "Events will appear when the frame reconnects."))
                events.isEmpty() -> addView(note(kit, "Nothing on the calendar for the next 7 days."))
                else -> TouchHomeAgenda.plan(events, nowMillis, ZoneId.systemDefault(), maxLines).also { plan ->
                    overflow = plan.hiddenCount
                }.sections.forEach { section ->
                    addView(kit.text(section.label, scale.label, TouchFonts.Face.TEXT_BOLD, if (section.isToday) p.accentInk else p.inkMuted),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(16) })
                    // Today carries a heavier accent rule so it stays distinct when accent and ink converge at night.
                    addView(View(context).apply { setBackgroundColor(if (section.isToday) p.accentStroke else p.line) },
                        LinearLayout.LayoutParams(MATCH, kit.dp(if (section.isToday) 3 else 1)).apply { topMargin = kit.dp(6) })
                    section.rows.forEach { row ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            contentDescription = "${section.label}, ${row.timeLabel}, ${row.title}"
                            addView(kit.text(row.timeLabel, scale.body, TouchFonts.Face.TEXT_BOLD).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                fontFeatureSettings = "tnum"
                            }, LinearLayout.LayoutParams(kit.dp((scale.body * 5.4f).toInt()), WRAP))
                            addView(kit.text(row.title, scale.body, maxLines = 1).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }, LinearLayout.LayoutParams(0, WRAP, 1f))
                        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(12) })
                    }
                }
            }
            if (overflow > 0) addOverflow(kit, overflow, canOpen)
        }
    }

    private fun LinearLayout.addOverflow(kit: TouchKit, hidden: Int, canOpen: Boolean) {
        val p = kit.palette
        addView(kit.hairline(), LinearLayout.LayoutParams(MATCH, kit.dp(1)).apply { topMargin = kit.dp(8) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = kit.dp(if (scale == TouchScale.WALL) 64 else 56)
            addView(kit.text("$hidden more ${if (hidden == 1) "event" else "events"} this week", scale.label, TouchFonts.Face.TEXT_BOLD, if (canOpen) p.accentInk else p.inkMuted))
            if (canOpen) addView(kit.icon(R.drawable.ic_touch_chevron_right, p.accentInk, 24), LinearLayout.LayoutParams(kit.dp(24), kit.dp(24)).apply { marginStart = kit.dp(4) })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun thermostat(kit: TouchKit, snapshot: TouchHomeModel?): View {
        val p = kit.palette
        val climate = snapshot?.climate
        val connection = snapshot?.connection
        return kit.panel().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(kit.text("Thermostat", scale.headline, TouchFonts.Face.DISPLAY_BOLD), LinearLayout.LayoutParams(0, WRAP, 1f))
                climate?.currentTemperature?.let {
                    addView(kit.text("Inside ${formatTemperature(it)}", scale.body, color = p.inkMuted))
                }
            })
            if (climate == null) {
                addView(when {
                    snapshot == null || (connection == TouchConnection.REFRESHING && snapshot.lastUpdatedEpochMillis == null) -> skeletonRows(kit, 2).let { rows ->
                        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; rows.forEach(::addView) }
                    }
                    connection == TouchConnection.SIGN_IN_REQUIRED -> note(kit, "Thermostat controls appear here after you sign in.")
                    connection == TouchConnection.OFFLINE -> note(kit, "Thermostat controls return when the frame reconnects.")
                    else -> note(kit, "This thermostat isn’t reporting a temperature range, so it can’t be adjusted here.")
                })
                return@apply
            }
            val canMutate = !climateBusy && connection == TouchConnection.CONNECTED
            val shown = draft.target ?: climate.targetTemperature
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val stepper = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(stepButton(kit, R.drawable.ic_touch_minus, "Lower setpoint", canMutate) { step(climate, -1) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val big = when {
                        shown != null -> formatTemperature(shown)
                        climate.mode.equals("off", true) -> "Off"
                        else -> "—"
                    }
                    addView(kit.text(big, scale.setpoint, TouchFonts.Face.DISPLAY_BOLD, if (draft.dirty) p.pendingInk else p.ink).apply {
                        gravity = Gravity.CENTER
                        fontFeatureSettings = "tnum"
                        contentDescription = if (draft.dirty) "New setpoint $big, not sent yet" else "Setpoint $big"
                    })
                    // Two lines are always reserved so the stepper and modes never move between states.
                    addView(FrameLayout(context).apply {
                        minimumHeight = kit.dp((scale.caption * 1.35f + scale.label * 1.5f + 6).toInt())
                        addView(setpointState(kit, climate, connection, shown), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(8) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(stepButton(kit, R.drawable.ic_touch_plus, "Raise setpoint", canMutate) { step(climate, 1) })
            }
            body.addView(stepper, LinearLayout.LayoutParams(MATCH, WRAP))
            // The slot keeps its height in every state so the stepper never jumps.
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val target = draft.target
            if (draft.dirty && !climateBusy && target != null) {
                actions.addView(kit.button("Cancel") {
                    removeCallbacks(revertDraft)
                    draft.reset(climate.targetTemperature)
                    climateResult = null
                    refillSlots()
                }, LinearLayout.LayoutParams(0, kit.dp(scale.target), 1f).apply { marginEnd = kit.dp(12) })
                val failed = climateResult == ClimateResult.FAILED
                actions.addView(kit.button(if (failed) "Try again" else "Set to ${formatTemperature(target)}", TouchKit.Emphasis.PRIMARY,
                    iconRes = if (failed) R.drawable.ic_touch_refresh else null, enabled = canMutate) {
                    onClimateTarget(target)
                }, LinearLayout.LayoutParams(0, kit.dp(scale.target), 1.4f))
            }
            body.addView(actions, LinearLayout.LayoutParams(MATCH, kit.dp(scale.target)).apply { topMargin = kit.dp(16) })
            val modes = climate.modes.distinct()
            if (modes.isNotEmpty()) {
                val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                modes.chunked(MODES_PER_ROW).forEachIndexed { index, chunk ->
                    rows.addView(kit.segmented(chunk.map { modeLabel(it) to it }, climate.mode.takeIf { it in chunk }, canMutate, scale.label) { mode ->
                        onClimateMode(mode)
                    }, LinearLayout.LayoutParams(MATCH, kit.dp(scale.target)).apply { if (index > 0) topMargin = kit.dp(8) })
                }
                body.addView(rows, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(scale.gap) })
            }
            // Stepper, action slot and modes stay together as one centred block on tall panels.
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(12) })
        }
    }

    private fun setpointState(kit: TouchKit, climate: TouchClimate, connection: TouchConnection?, shown: Double?): View = when {
        climateBusy -> kit.chip("Sending…", TouchKit.Tone.PENDING)
        climateResult == ClimateResult.FAILED && draft.dirty && shown != null -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(kit.chip("Couldn’t set to ${formatTemperature(shown)}", TouchKit.Tone.ERROR))
            // The command path reports only confirmed or not, so the reason is stated at that level.
            addView(kit.text("Home Assistant didn’t confirm it.", scale.caption, color = kit.palette.inkMuted),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(6) })
        }
        climateResult == ClimateResult.FAILED -> kit.chip("Couldn’t confirm the change", TouchKit.Tone.ERROR)
        draft.dirty -> kit.chip("Not sent yet", TouchKit.Tone.PENDING)
        climateResult == ClimateResult.CONFIRMED && shown != null -> kit.chip("Set to ${formatTemperature(shown)}", TouchKit.Tone.OK)
        connection != TouchConnection.CONNECTED -> kit.text("Reconnect to change", scale.label, color = kit.palette.inkMuted)
        else -> kit.text(modeSentence(climate, shown), scale.label, color = kit.palette.inkMuted)
    }

    private fun stepButton(kit: TouchKit, iconRes: Int, description: String, enabled: Boolean, action: () -> Unit): View {
        val p = kit.palette
        val size = kit.dp(scale.stepper)
        return FrameLayout(context).apply {
            contentDescription = description
            isClickable = enabled
            isFocusable = enabled
            isEnabled = enabled
            val base = if (enabled) kit.oval(p.matRaised, p.stroke) else android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(kit.dp(2), p.disabledInk, kit.dp(6).toFloat(), kit.dp(5).toFloat())
            }
            background = if (enabled) android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(p.pressed), base, kit.oval(0xFFFFFFFF.toInt(), null)) else base
            addView(kit.icon(iconRes, if (enabled) p.ink else p.disabledInk, (scale.stepper * 0.4f).toInt()), FrameLayout.LayoutParams(
                kit.dp((scale.stepper * 0.4f).toInt()), kit.dp((scale.stepper * 0.4f).toInt()), Gravity.CENTER))
            if (enabled) setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun step(climate: TouchClimate, direction: Int) {
        draft.step(climate, direction) ?: return
        climateResult = null
        removeCallbacks(revertDraft)
        if (draft.dirty) postDelayed(revertDraft, DRAFT_TIMEOUT_MILLIS)
        refillSlots()
    }

    /** A change nobody sent within 30 s is abandoned; an unattended frame must show the real setpoint. */
    private val revertDraft = Runnable {
        if (!draft.dirty || climateBusy) return@Runnable
        draft.reset(model?.climate?.targetTemperature)
        climateResult = null
        refillSlots()
    }

    private val settleResult = Runnable {
        if (climateResult != ClimateResult.CONFIRMED) return@Runnable
        climateResult = null
        refillSlots()
    }

    private fun status(kit: TouchKit, snapshot: TouchHomeModel?): View? {
        val p = kit.palette
        val updated = snapshot?.lastUpdatedEpochMillis
        val text = when (snapshot?.connection) {
            TouchConnection.CONNECTED -> updated?.let { "Updated ${timeAt(it)}" } ?: "Connected"
            TouchConnection.REFRESHING -> updated?.let { "Refreshing. Last updated ${timeAt(it)}" } ?: "Loading home data\u2026"
            // The offline banner already gives the saved time; sign-in has its own panel.
            TouchConnection.OFFLINE, TouchConnection.SIGN_IN_REQUIRED -> return null
            null -> "Loading home data\u2026"
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val live = snapshot?.connection == TouchConnection.CONNECTED
            addView(View(context).apply { background = kit.oval(if (live) p.accent else 0x00000000, if (live) null else p.inkMuted) },
                LinearLayout.LayoutParams(kit.dp(12), kit.dp(12)))
            addView(kit.text(text, scale.caption, color = p.inkMuted), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(10) })
        }
    }

    private fun note(kit: TouchKit, message: String): View =
        kit.text(message, scale.body, color = kit.palette.inkMuted).apply { setPadding(0, kit.dp(24), 0, 0) }

    /** Loading placeholders shaped like the rows they stand in for (first launch only). */
    private fun skeletonRows(kit: TouchKit, count: Int): List<View> = (0 until count).map { index ->
        View(context).apply {
            background = kit.rounded(kit.palette.line, 8)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(kit.dp(if (index % 2 == 0) 420 else 300), kit.dp((scale.body * 0.9f).toInt())).apply {
                topMargin = kit.dp(if (index == 0) 28 else 18)
            }
        }
    }

    private fun glyphIcon(state: String): Int = when (TouchWeatherLabel.glyph(state)) {
        TouchWeatherLabel.Glyph.SUN -> R.drawable.ic_touch_sun
        TouchWeatherLabel.Glyph.MOON -> R.drawable.ic_touch_moon
        TouchWeatherLabel.Glyph.CLOUD -> R.drawable.ic_touch_cloud
        TouchWeatherLabel.Glyph.RAIN -> R.drawable.ic_touch_rain
        TouchWeatherLabel.Glyph.SNOW -> R.drawable.ic_touch_snow
        TouchWeatherLabel.Glyph.STORM -> R.drawable.ic_touch_storm
        TouchWeatherLabel.Glyph.NONE -> R.drawable.ic_touch_weather
    }

    private fun isLargeLandscape(): Boolean {
        val density = resources.displayMetrics.density
        val widthDp = if (width > 0) width / density else resources.configuration.screenWidthDp.toFloat()
        val heightDp = if (height > 0) height / density else resources.configuration.screenHeightDp.toFloat()
        return TouchHomeLayout.isLargeLandscape(widthDp, heightDp)
    }

    private fun is24Hour(): Boolean = DateFormat.is24HourFormat(context)

    private fun timeText(): String = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (is24Hour()) "H:mm" else "h:mm", Locale.getDefault()))

    private fun suffixText(): String = if (is24Hour()) "" else Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("a", Locale.getDefault()))

    private fun dateText(): String = Instant.ofEpochMilli(nowMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))

    private fun timeAt(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (is24Hour()) "H:mm" else "h:mm a", Locale.getDefault()))

    private fun hourAt(epochMillis: Long): Int = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).hour

    private fun dayAt(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun modeLabel(mode: String): String = when (mode) {
        "heat_cool" -> "Heat & cool"
        "fan_only" -> "Fan"
        else -> mode.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun modeSentence(climate: TouchClimate, target: Double?): String {
        val value = target?.let { formatTemperature(it) }
        return when (climate.mode) {
            "heat" -> value?.let { "Heats to $it" } ?: "Heat"
            "cool" -> value?.let { "Cools to $it" } ?: "Cool"
            "off" -> "Thermostat is off"
            else -> modeLabel(climate.mode)
        }
    }

    /** Thermostat values read as "72°"; the unit (°F/°C) is the household's own and is shown nowhere else. */
    private fun formatTemperature(value: Double): String =
        (if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(Locale.getDefault(), value)) + "\u00B0"

    private companion object {
        const val MODES_PER_ROW = 4
        const val DRAFT_TIMEOUT_MILLIS = 30_000L
        const val CONFIRMATION_MILLIS = 5_000L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

internal object TouchHomeLayout {
    const val LARGE_LANDSCAPE_DP = 900f

    fun contentWidthDp(screenWidthDp: Float, railWidthDp: Float): Float =
        (screenWidthDp - railWidthDp).coerceAtLeast(0f)

    fun isLargeLandscape(widthDp: Float, heightDp: Float): Boolean =
        widthDp >= LARGE_LANDSCAPE_DP && (heightDp <= 0f || widthDp >= heightDp)

    fun needsTransition(previous: Boolean?, next: Boolean): Boolean = previous != next
}

internal class TouchHomeLayoutTransition {
    private var rendered: Boolean? = null
    private var requested: Boolean? = null

    fun markRendered(value: Boolean) {
        rendered = value
        if (requested == value) requested = null
    }

    fun request(next: Boolean): Boolean {
        if (!TouchHomeLayout.needsTransition(rendered, next)) {
            requested = null
            return false
        }
        if (requested == next) return false
        requested = next
        return true
    }

    fun consume(): Boolean? {
        val next = requested ?: return null
        requested = null
        if (!TouchHomeLayout.needsTransition(rendered, next)) return null
        rendered = next
        return next
    }
}
