package com.wyattfleming.touchframe.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wyattfleming.touchframe.home.TouchCalendarEvent
import com.wyattfleming.touchframe.home.TouchClimate
import com.wyattfleming.touchframe.home.TouchConnection
import com.wyattfleming.touchframe.home.TouchHomeModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.round

/**
 * Native, scrollable Home surface. The activity supplies live data on its
 * background refresh path; this view does not make Home Assistant or web calls.
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

    private var model: TouchHomeModel? = null
    private var destinations: List<TouchDestination> = TouchDestinationOrder.defaults
    private var nowMillis: Long = System.currentTimeMillis()
    private var style = HomeStyle.forHour(hourAt(nowMillis))
    private var draftTarget: Double? = null
    private var draftDirty = false
    private var climateBusy = false
    private var climateMessage: String? = null
    private var clockView: TextView? = null
    private var dateView: TextView? = null
    private var scrollView: ScrollView? = null
    private var scrollPosition = 0
    private var lastLargeLayout: Boolean? = null

    init {
        isFocusable = true
        rebuild()
    }

    /** Applies already-supported remote modes in their requested order; Home remains first. */
    fun setDestinations(remoteOrder: List<TouchDestination>) {
        val normalized = TouchDestinationOrder.normalize(remoteOrder)
        if (destinations == normalized) return
        destinations = normalized
        rebuild()
    }

    /** Renders the newest caller-owned snapshot without clearing an active draft. */
    fun render(model: TouchHomeModel) {
        if (this.model == model) return
        val previousClimate = this.model?.climate
        this.model = model
        val climate = model.climate
        if (climate?.entityId != previousClimate?.entityId) clearDraft(climate)
        else if (!draftDirty) clearDraft(climate)
        rebuild()
    }

    /** Lets the activity reflect a real command outcome without inventing one. */
    fun setClimateBusy(busy: Boolean, message: String? = null) {
        if (climateBusy == busy && climateMessage == message) return
        climateBusy = busy
        climateMessage = message
        rebuild()
    }

    /** Updates the retained clock nodes; only a day/night transition rebuilds cards. */
    fun tick(nowMillis: Long) {
        this.nowMillis = nowMillis
        val nextStyle = HomeStyle.forHour(hourAt(nowMillis))
        if (nextStyle != style) {
            style = nextStyle
            rebuild()
        } else {
            clockView?.text = timeText()
            dateView?.text = dateText()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val large = isLargeLandscape()
        if (lastLargeLayout != null && lastLargeLayout != large) rebuild()
        lastLargeLayout = large
    }

    private fun rebuild() {
        scrollPosition = scrollView?.scrollY ?: scrollPosition
        removeAllViews()
        val dashboard = model
        val type = typography()
        val large = isLargeLandscape()
        setBackgroundColor(style.background)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        scrollView = scroll
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(18), context.dp(24), context.dp(28))
        }
        content.addView(glanceHeader(dashboard, type, large))
        content.addView(statusRow(dashboard, type), LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(8) })
        val overview = LinearLayout(context).apply {
            orientation = if (large) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val events = eventsCard(dashboard?.events.orEmpty(), type)
        val climate = climateCard(dashboard?.climate, dashboard?.connection, type)
        if (large) {
            overview.addView(events, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = context.dp(16)
            })
            overview.addView(climate, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        } else {
            overview.addView(events)
            overview.addView(climate, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(16) })
        }
        content.addView(overview, LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(12) })
        if (destinations.any { it != TouchDestination.HOME }) {
            content.addView(exploreTiles(type), LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(16) })
        }
        scroll.addView(content, ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scroll.post { scroll.scrollTo(0, scrollPosition.coerceAtLeast(0)) }
    }

    private fun glanceHeader(snapshot: TouchHomeModel?, type: Typography, large: Boolean): View = LinearLayout(context).apply {
        orientation = if (large) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val time = header(type)
        val weather = weatherHeader(snapshot, type)
        if (large) {
            addView(time, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.1f).apply { marginEnd = context.dp(20) })
            addView(weather, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.9f))
        } else {
            addView(time)
            addView(weather, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(10)
            })
        }
    }

    private fun header(type: Typography): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val clock = label(timeText(), type.clock, true)
        val date = label(dateText(), type.body, false)
        clockView = clock
        dateView = date
        addView(clock)
        addView(date)
    }

    /** A thin state row keeps Home glanceable; sign-in has exactly one entry point. */
    private fun statusRow(snapshot: TouchHomeModel?, type: Typography): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(connectionText(snapshot?.connection), type.small, true), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        snapshot?.lastUpdatedEpochMillis?.let { updated ->
            addView(label("Updated ${timeAt(updated)}", type.small, false))
        }
        if (snapshot?.connection == TouchConnection.SIGN_IN_REQUIRED) {
            addView(button("Sign in", type.small) { onSignIn() }, LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                context.dp(56),
            ).apply { marginStart = context.dp(12) })
        }
    }

    private fun weatherHeader(snapshot: TouchHomeModel?, type: Typography): View = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        addView(label("Weather", type.small, true))
        val weather = snapshot?.weatherText?.takeIf { it.isNotBlank() } ?: "Weather unavailable"
        val temperature = snapshot?.temperatureText?.takeIf { it.isNotBlank() } ?: "Temperature unavailable"
        addView(label(weather, type.body, true))
        addView(label(temperature, type.small, false))
    }

    private fun eventsCard(events: List<TouchCalendarEvent>, type: Typography): View = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        addView(label("Up next", type.small, true))
        if (events.isEmpty()) {
            addView(label("No upcoming events", type.body, false))
        } else {
            events.take(MAX_EVENTS).forEachIndexed { index, event ->
                if (index > 0) addView(divider())
                addView(label(event.title, type.body, true))
                addView(label(eventTime(event), type.small, false))
            }
        }
    }

    private fun climateCard(climate: TouchClimate?, connection: TouchConnection?, type: Typography): View = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        addView(label("Climate", type.small, true))
        if (climate == null) {
            addView(label("Climate unavailable", type.body, false))
            return@apply
        }
        val target = draftTarget ?: climate.targetTemperature
        val canMutate = !climateBusy && connection == TouchConnection.CONNECTED
        addView(label(
            climate.currentTemperature?.let { "Current ${formatTemperature(it, climate.unit)}" } ?: "Current temperature unavailable",
            type.body,
            true,
        ))
        addView(label(
            target?.let { "Set to ${formatTemperature(it, climate.unit)}" } ?: "Target temperature unavailable",
            type.small,
            false,
        ))
        val modes = twoColumnGrid(climate.modes.distinct()) { candidate ->
            modeButton(
                candidate.replaceFirstChar { it.titlecase(Locale.getDefault()) },
                type.small,
                selected = candidate == climate.mode,
                enabled = canMutate,
            ) { onClimateMode(candidate) }
        }
        addView(modes, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8)
        })
        val adjust = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        adjust.addView(button("−", type.body, enabled = canMutate) { changeTarget(climate, -1) }, LinearLayout.LayoutParams(0, context.dp(56), 1f).apply {
            marginEnd = context.dp(8)
        })
        adjust.addView(button("+", type.body, enabled = canMutate) { changeTarget(climate, 1) }, LinearLayout.LayoutParams(0, context.dp(56), 1f))
        addView(adjust, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8)
        })
        val apply = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        apply.addView(button("Apply", type.small, enabled = canMutate && draftDirty) {
            draftTarget?.takeIf { it != climate.targetTemperature }?.let(onClimateTarget)
        }, LinearLayout.LayoutParams(0, context.dp(56), 1f).apply { marginEnd = context.dp(8) })
        apply.addView(button("Cancel", type.small, enabled = canMutate && draftDirty) {
            clearDraft(climate)
            rebuild()
        }, LinearLayout.LayoutParams(0, context.dp(56), 1f))
        addView(apply, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8)
        })
        climateMessage?.let { message ->
            addView(label(message, type.small, false), LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = context.dp(8) })
        }
    }

    private fun exploreTiles(type: Typography): View = card().apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        addView(label("Explore", type.small, true))
        addView(twoColumnGrid(TouchDestinationOrder.explore(destinations)) { destination ->
            button(destination.label, type.small) { onNavigate(destination.name.lowercase(Locale.ROOT)) }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8)
        })
    }

    private fun twoColumnGrid(
        values: List<String>,
        factory: (String) -> View,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        values.chunked(2).forEachIndexed { rowIndex, row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { columnIndex, value ->
                line.addView(factory(value), LinearLayout.LayoutParams(0, context.dp(56), 1f).apply {
                    if (columnIndex > 0) marginStart = context.dp(8)
                })
            }
            if (row.size == 1) line.addView(View(context), LinearLayout.LayoutParams(0, context.dp(56), 1f).apply { marginStart = context.dp(8) })
            addView(line, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(56)).apply {
                if (rowIndex > 0) topMargin = context.dp(8)
            })
        }
    }

    private fun modeButton(label: String, size: Float, selected: Boolean, enabled: Boolean, action: () -> Unit): TextView =
        button(label, size, enabled, action).apply {
            if (selected) {
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(14).toFloat()
                    setColor(style.ink)
                }
                setTextColor(style.surface)
            }
        }

    private fun changeTarget(climate: TouchClimate, direction: Int) {
        val initial = draftTarget ?: climate.targetTemperature ?: climate.currentTemperature ?: return
        // A missing HA capability uses an explicit local display increment, never a server claim.
        val increment = climate.step ?: if (climate.unit.contains("c", ignoreCase = true)) 0.5 else 1.0
        val bounded = (initial + direction * increment).coerceIn(climate.minTemperature, climate.maxTemperature)
        val steps = round((bounded - climate.minTemperature) / increment)
        draftTarget = (climate.minTemperature + steps * increment).coerceIn(climate.minTemperature, climate.maxTemperature)
        draftDirty = true
        rebuild()
    }

    private fun clearDraft(climate: TouchClimate?) {
        draftTarget = climate?.targetTemperature
        draftDirty = false
    }

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = style.card(context.dp(20).toFloat())
    }

    private fun View.applyMinimumTouchTarget() {
        minimumHeight = context.dp(56)
        minimumWidth = context.dp(56)
    }

    private fun button(label: String, size: Float, enabled: Boolean = true, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        isClickable = enabled
        isFocusable = enabled
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.45f
        applyMinimumTouchTarget()
        background = style.card(context.dp(14).toFloat())
        setPadding(context.dp(12), 0, context.dp(12), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.create("sans", Typeface.BOLD)
        setTextColor(style.ink)
        setOnClickListener { action() }
    }

    private fun label(text: String, size: Float, strong: Boolean): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.create("sans", if (strong) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(if (strong) style.ink else style.muted)
        includeFontPadding = false
        setPadding(0, context.dp(3), 0, context.dp(3))
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(style.outline)
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1)).apply {
            topMargin = context.dp(8)
            bottomMargin = context.dp(8)
        }
    }

    private fun isLargeLandscape(): Boolean {
        val density = resources.displayMetrics.density
        val widthDp = if (width > 0) width / density else resources.configuration.screenWidthDp.toFloat()
        return widthDp >= LARGE_LANDSCAPE_DP && (height == 0 || width >= height)
    }

    private fun typography(): Typography = if (isLargeLandscape()) {
        Typography(body = 24f, small = 20f, clock = 108f)
    } else {
        Typography(body = 18f, small = 16f, clock = 72f)
    }

    private fun timeText(): String = timeAt(nowMillis)

    private fun dateText(): String = Instant.ofEpochMilli(nowMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))

    private fun timeAt(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))

    private fun hourAt(epochMillis: Long): Int = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).hour

    private fun eventTime(event: TouchCalendarEvent): String = if (event.allDay) {
        "All day"
    } else {
        timeAt(event.startEpochMillis)
    }

    private fun connectionText(connection: TouchConnection?): String = when (connection) {
        TouchConnection.CONNECTED -> "Connected"
        TouchConnection.REFRESHING -> "Refreshing in background"
        TouchConnection.OFFLINE -> "Offline — showing available data"
        TouchConnection.SIGN_IN_REQUIRED -> "Sign in required"
        null -> "Home data unavailable"
    }

    private fun formatTemperature(value: Double, unit: String): String = "%s%s".format(
        Locale.getDefault(),
        if (value % 1.0 == 0.0) value.toInt() else "%.1f".format(Locale.getDefault(), value),
        unit,
    )

    private data class Typography(val body: Float, val small: Float, val clock: Float)

    private data class HomeStyle(
        val background: Int,
        val surface: Int,
        val ink: Int,
        val muted: Int,
        val outline: Int,
    ) {
        fun card(radius: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(surface)
            setStroke(1, outline)
            cornerRadius = radius
        }

        companion object {
            fun forHour(hour: Int): HomeStyle = if (hour in 7..18) {
                HomeStyle(
                    background = Color.rgb(244, 240, 230),
                    surface = Color.rgb(255, 253, 247),
                    ink = Color.rgb(20, 39, 65),
                    muted = Color.rgb(88, 103, 116),
                    outline = Color.rgb(216, 211, 199),
                )
            } else {
                HomeStyle(
                    background = Color.rgb(13, 29, 49),
                    surface = Color.rgb(23, 45, 69),
                    ink = Color.rgb(247, 244, 235),
                    muted = Color.rgb(184, 197, 204),
                    outline = Color.rgb(54, 79, 99),
                )
            }
        }
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LARGE_LANDSCAPE_DP = 900f
        const val MAX_EVENTS = 5
    }
}
