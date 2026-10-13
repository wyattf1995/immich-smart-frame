package com.wyattfleming.touchframe.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import com.wyattfleming.touchframe.R
import kotlin.math.abs
import kotlin.math.pow

/**
 * Owns only persistent shell chrome: a top navigation bar (the frame's moulding along the top
 * edge) with tabs, the time and Settings. The caller owns each destination's content and
 * lifecycle. Media destinations keep the content frame edge-to-edge until a deliberate left-edge
 * gesture drops this bar down over them.
 */
// Constructed by TouchFrameActivity with callbacks; never inflated from XML.
@SuppressLint("ViewConstructor")
class TouchShellLayout(
    context: Context,
    private val onNavigate: (TouchDestination) -> Unit,
    private val onSettings: () -> Unit,
) : FrameLayout(context) {
    val content: FrameLayout = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val scrim = FrameLayout(context).apply {
        setBackgroundColor(Color.argb(145, 0, 0, 0))
        contentDescription = context.getString(com.wyattfleming.touchframe.R.string.return_to_navigation)
        isClickable = true
        setOnClickListener { hideMenu() }
    }
    /** Tabs scroll sideways (with fading edges) rather than truncate or wrap under large fonts or a seventh tab. */
    private val tabViewport = HorizontalScrollView(context).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
    }
    private val tabs = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val clock = TextView(context).apply {
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private var clockText: CharSequence = ""
    private val noticeHost = FrameLayout(context)
    private val edgeHint = FrameLayout(context).apply {
        visibility = View.GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var destination = TouchDestination.HOME
    private var destinations: List<TouchDestination> = TouchDestinationOrder.defaults
    private var night = false
    private var menuVisible = false
    private var noticeVisible = false
    private val edgeStream = TouchShellEdgeStream()
    private val hideNotice = Runnable { clearNotice() }
    private val hideEdgeHint = Runnable { edgeHint.animate().alpha(0f).setDuration(400L).withEndAction { edgeHint.visibility = View.GONE } }

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(noticeHost, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        addView(edgeHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(barHeightDpFor(resources.configuration.screenWidthDp)), Gravity.TOP))
        bar.addView(tabViewport, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        bar.addView(clock, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        scrim.addView(TextView(context).apply {
            text = context.getString(R.string.touch_menu_close_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_MEDIUM)
            setTextColor(0xFFF6F4EF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xC7101211.toInt())
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        tabViewport.addView(tabs, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        select(TouchDestination.HOME)
    }

    fun select(next: TouchDestination) {
        if (next != destination) {
            removeCallbacks(hideNotice)
            noticeHost.removeAllViews()
            noticeVisible = false
            if (!next.isImmersiveMedia) hideEdgeHintNow()
        }
        destination = next
        menuVisible = !next.isImmersiveMedia
        applyChrome()
    }

    /** Applies already-supported remote modes in their requested order; Home remains first. */
    fun setDestinations(remoteOrder: List<TouchDestination>) {
        val normalized = TouchDestinationOrder.normalize(remoteOrder)
        if (destinations == normalized) return
        destinations = normalized
        val selected = TouchDestinationOrder.selectedOrHome(destination, destinations)
        if (selected != destination) {
            select(selected)
            onNavigate(selected)
        } else {
            applyChrome()
        }
    }

    fun showMenu() {
        if (!destination.isImmersiveMedia) return
        hideEdgeHintNow()
        menuVisible = true
        applyChrome()
    }

    fun hideMenu() {
        if (!destination.isImmersiveMedia) return
        menuVisible = false
        applyChrome()
    }

    /** The bar carries the time on every screen except Home, whose clock is the page itself. */
    fun setClock(text: CharSequence) {
        if (text == clockText) return
        clockText = text
        clock.text = text
    }

    fun setNight(isNight: Boolean) {
        if (night == isNight) return
        night = isNight
        applyChrome()
    }

    enum class NoticeTone { INFO, ERROR }

    /**
     * Shell-owned replacement for Toasts: readable from across a room, optionally actionable.
     * Information hides after 6 s; errors stay 30 s or until dismissed so an unattended frame
     * never keeps a stale error forever. Changing destination clears it.
     */
    fun showNotice(message: String, tone: NoticeTone = NoticeTone.INFO, actionLabel: String? = null, action: (() -> Unit)? = null) {
        removeCallbacks(hideNotice)
        noticeHost.removeAllViews()
        val kit = TouchKit(context, TouchPalette.forNight(night), TouchScale.forContentHeightDp(height / resources.displayMetrics.density))
        val p = kit.palette
        val (fill, ink, stroke) = if (tone == NoticeTone.ERROR) Triple(p.alert, p.alertInk, p.alertInk) else Triple(p.notice, p.noticeInk, p.noticeStroke)
        val notice = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = kit.rounded(fill, kit.scale.radius, stroke, 2)
            minimumHeight = kit.dp(kit.scale.target + 16)
            setPadding(kit.dp(24), kit.dp(8), kit.dp(if (tone == NoticeTone.ERROR || actionLabel != null) 8 else 28), kit.dp(8))
            accessibilityLiveRegion = if (tone == NoticeTone.ERROR) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(kit.icon(if (tone == NoticeTone.ERROR) R.drawable.ic_touch_alert else R.drawable.ic_touch_check, ink, 30))
            addView(kit.text(message, kit.scale.body, TouchFonts.Face.TEXT_MEDIUM, ink, maxLines = 2), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = kit.dp(16)
                marginEnd = kit.dp(16)
            })
            if (actionLabel != null && action != null) addView(kit.button(actionLabel, TouchKit.Emphasis.QUIET, quietInk = ink) {
                clearNotice()
                action()
            })
            if (tone == NoticeTone.ERROR) addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                minimumWidth = kit.dp(kit.scale.target)
                minimumHeight = kit.dp(kit.scale.target)
                isClickable = true
                isFocusable = true
                contentDescription = "Dismiss"
                addView(kit.icon(R.drawable.ic_touch_close, ink, 28))
                setOnClickListener { clearNotice() }
            })
        }
        noticeHost.addView(notice, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        noticeVisible = true
        applyChrome()
        postDelayed(hideNotice, if (tone == NoticeTone.ERROR) ERROR_NOTICE_MILLIS else INFO_NOTICE_MILLIS)
    }

    /** A brief, non-interactive cue that the menu lives behind the left edge of full-screen media. */
    fun showEdgeHint() {
        if (!destination.isImmersiveMedia || menuVisible) return
        val kit = TouchKit(context, TouchPalette.forNight(night), TouchScale.forContentHeightDp(height / resources.displayMetrics.density))
        val ink = if (night) kit.palette.ink else 0xFFF6F4EF.toInt()
        edgeHint.removeAllViews()
        edgeHint.addView(View(context).apply {
            background = kit.rounded(if (night) 0x8CB39668.toInt() else 0x99F6F4EF.toInt(), 4)
        }, FrameLayout.LayoutParams(kit.dp(8), kit.dp(96), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = kit.dp(10) })
        edgeHint.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = kit.rounded(if (night) 0xD1000000.toInt() else 0xC7101211.toInt(), kit.scale.radiusSmall)
            setPadding(kit.dp(18), kit.dp(14), kit.dp(22), kit.dp(14))
            addView(kit.icon(R.drawable.ic_touch_chevron_right, ink, 26))
            addView(kit.text(context.getString(R.string.touch_edge_hint), kit.scale.label, TouchFonts.Face.TEXT_MEDIUM, ink),
                LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = kit.dp(10) })
        }, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = kit.dp(34) })
        removeCallbacks(hideEdgeHint)
        edgeHint.animate().cancel()
        edgeHint.alpha = 1f
        edgeHint.visibility = View.VISIBLE
        postDelayed(hideEdgeHint, EDGE_HINT_MILLIS)
    }

    /** Removes the notice and gives its docked strip back to the destination. */
    private fun clearNotice() {
        removeCallbacks(hideNotice)
        noticeHost.removeAllViews()
        if (!noticeVisible) return
        noticeVisible = false
        applyChrome()
    }

    private fun hideEdgeHintNow() {
        removeCallbacks(hideEdgeHint)
        edgeHint.animate().cancel()
        edgeHint.visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideNotice)
        removeCallbacks(hideEdgeHint)
        super.onDetachedFromWindow()
    }

    /**
     * Claims qualifying media-edge streams at DOWN, before child hit testing. Retry/loading covers
     * can be non-clickable, so waiting for interception leaves their later MOVE events unobserved.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val result = edgeStream.onEvent(
            action = event.actionMasked,
            x = event.x,
            y = event.y,
            destination = destination,
            menuVisible = menuVisible,
            edgeWidth = dp(EDGE_WIDTH_DP),
            horizontalThreshold = dp(HORIZONTAL_THRESHOLD_DP),
            verticalTolerance = dp(VERTICAL_TOLERANCE_DP),
        )
        if (result.reveal) showMenu()
        return result.ownsStream || super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!menuVisible) return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyChrome() {
        val palette = Palette.forNight(night)
        val barHeight = dp(barHeightDpFor(resources.configuration.screenWidthDp))
        val large = barHeightDpFor(resources.configuration.screenWidthDp) >= WIDE_BAR_HEIGHT_DP
        setBackgroundColor(palette.background)
        bar.setBackgroundColor(palette.rail)
        bar.setPadding(dp(8), 0, dp(8), 0)
        bar.layoutParams = (bar.layoutParams as LayoutParams).apply { height = barHeight }
        rebuildTabs(palette, barHeight, large)
        clock.typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (large) 30f else 27f)
        clock.setTextColor(palette.ink)
        clock.setPadding(dp(16), 0, dp(16), 0)
        clock.text = clockText
        clock.visibility = if (destination == TouchDestination.HOME) View.GONE else View.VISIBLE

        bar.visibility = if (menuVisible) View.VISIBLE else View.GONE
        scrim.visibility = if (destination.isImmersiveMedia && menuVisible) View.VISIBLE else View.GONE
        (scrim.getChildAt(0)?.layoutParams as? LayoutParams)?.topMargin = barHeight + dp(40)
        (scrim.getChildAt(0) as? TextView)?.setTextColor(if (night) TouchPalette.NIGHT.ink else 0xFFF6F4EF.toInt())
        content.layoutParams = (content.layoutParams as LayoutParams).apply {
            topMargin = if (destination.isImmersiveMedia) 0 else barHeight
            leftMargin = 0
            // A docked notice takes a strip under native content instead of covering its controls.
            bottomMargin = if (noticeVisible && !destination.isImmersiveMedia) noticeStripHeight() else 0
        }
        content.requestLayout()
        positionOverlays()
    }

    /** Docks notices at the start of the content area, inside the strip reserved by [applyChrome]. */
    private fun positionOverlays() {
        val scale = TouchScale.forContentHeightDp(height / resources.displayMetrics.density)
        noticeHost.layoutParams = (noticeHost.layoutParams as LayoutParams).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(scale.pad)
            bottomMargin = dp(scale.pad / 2)
        }
    }

    private fun noticeStripHeight(): Int {
        val scale = TouchScale.forContentHeightDp(height / resources.displayMetrics.density)
        return dp(scale.target + 16 + scale.pad / 2)
    }

    private fun rebuildTabs(palette: Palette, barHeight: Int, large: Boolean) {
        tabs.removeAllViews()
        destinations.forEach { item -> tabs.addView(destinationTab(item, palette, barHeight, large)) }
        if (bar.childCount > 2) bar.removeViewAt(2)
        bar.addView(settingsTab(palette, barHeight, large))
    }

    private fun destinationTab(item: TouchDestination, palette: Palette, barHeight: Int, large: Boolean): View {
        val selected = item == destination
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = item.label
            minimumWidth = dp(MIN_TARGET_DP)
            setPadding(dp(if (large) 24 else 18), 0, dp(if (large) 24 else 18), 0)
            background = if (selected) rounded(palette.selected, palette.selectedStroke) else rounded(Color.TRANSPARENT, null)
            setOnClickListener {
                hideMenu()
                onNavigate(item)
            }
            addView(icon(item.icon, if (selected) palette.selectedInk else palette.muted, large))
            addView(label(item.label, if (selected) palette.selectedInk else palette.ink, large))
        }.also { view ->
            view.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, barHeight - dp(TAB_INSET_DP * 2)).apply {
                marginEnd = dp(4)
            }
        }
    }

    private fun settingsTab(palette: Palette, barHeight: Int, large: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = "Settings"
        minimumWidth = dp(MIN_TARGET_DP)
        setPadding(dp(18), 0, dp(18), 0)
        background = rounded(palette.settings, palette.settingsStroke)
        setOnClickListener { onSettings() }
        addView(icon(R.drawable.ic_touch_settings, palette.settingsInk, large))
        addView(label("Settings", palette.settingsInk, large))
    }.also { view ->
        view.layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, barHeight - dp(TAB_INSET_DP * 2))
    }

    private val TouchDestination.icon: Int
        get() = when (this) {
            TouchDestination.HOME -> R.drawable.ic_touch_home
            TouchDestination.PHOTOS -> R.drawable.ic_touch_photos
            TouchDestination.ART -> R.drawable.ic_touch_art
            TouchDestination.BIRDS -> R.drawable.ic_touch_birds
            TouchDestination.WEATHER -> R.drawable.ic_touch_weather
            TouchDestination.CAMERAS -> R.drawable.ic_touch_cameras
            TouchDestination.CALENDAR -> R.drawable.ic_touch_calendar
        }

    private fun icon(resource: Int, color: Int, large: Boolean): ImageView = ImageView(context).apply {
        setImageDrawable(context.getDrawable(resource)?.mutate()?.apply { setTint(color) })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = dp(if (large) 30 else 26)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(10) }
    }

    /** Single-line label in one weight, so selection never changes a tab's width. */
    private fun label(value: String, color: Int, large: Boolean): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_MEDIUM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (large) 22f else 19f)
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        maxLines = 1
    }

    private fun rounded(color: Int, stroke: Int?) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    internal companion object {
        const val EDGE_WIDTH_DP = 48
        const val HORIZONTAL_THRESHOLD_DP = 56
        const val VERTICAL_TOLERANCE_DP = 32
        const val MIN_TARGET_DP = 56
        const val PANEL_BAR_HEIGHT_DP = 68
        const val WIDE_BAR_HEIGHT_DP = 80
        private const val TAB_INSET_DP = 6
        private const val INFO_NOTICE_MILLIS = 6_000L
        private const val ERROR_NOTICE_MILLIS = 30_000L
        private const val EDGE_HINT_MILLIS = 4_000L

        fun barHeightDpFor(screenWidthDp: Int): Int = if (screenWidthDp >= 1400) WIDE_BAR_HEIGHT_DP else PANEL_BAR_HEIGHT_DP

    }
}

internal object TouchShellGesture {
    fun shouldReveal(
        destination: TouchDestination,
        downX: Float,
        downY: Float,
        upX: Float,
        upY: Float,
        edgeWidth: Int,
        horizontalThreshold: Int = TouchShellLayout.HORIZONTAL_THRESHOLD_DP,
        verticalTolerance: Int = TouchShellLayout.VERTICAL_TOLERANCE_DP,
    ): Boolean = destination.isImmersiveMedia &&
        downX in 0f..edgeWidth.toFloat() &&
        upX - downX >= horizontalThreshold &&
        abs(upY - downY) <= verticalTolerance
}

/** Stateful ownership policy for an edge stream claimed before any child accepts DOWN. */
internal class TouchShellEdgeStream {
    private var active = false
    private var revealed = false
    private var downX = 0f
    private var downY = 0f

    data class Result(val ownsStream: Boolean, val reveal: Boolean)

    fun onEvent(
        action: Int,
        x: Float,
        y: Float,
        destination: TouchDestination,
        menuVisible: Boolean,
        edgeWidth: Int,
        horizontalThreshold: Int = TouchShellLayout.HORIZONTAL_THRESHOLD_DP,
        verticalTolerance: Int = TouchShellLayout.VERTICAL_TOLERANCE_DP,
    ): Result = when (action) {
        MotionEvent.ACTION_DOWN -> {
            active = destination.isImmersiveMedia && !menuVisible && x in 0f..edgeWidth.toFloat()
            revealed = false
            downX = x
            downY = y
            Result(active, false)
        }
        MotionEvent.ACTION_MOVE -> {
            if (!active) {
                Result(false, false)
            } else {
                val revealNow = !revealed && TouchShellGesture.shouldReveal(
                    destination, downX, downY, x, y, edgeWidth, horizontalThreshold, verticalTolerance,
                )
                revealed = revealed || revealNow
                Result(true, revealNow)
            }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            val ownsStream = active
            active = false
            revealed = false
            Result(ownsStream, false)
        }
        else -> Result(active, false)
    }
}

/** Rail colors, derived from the shared [TouchPalette] so the rail and screens never drift. */
internal data class Palette(
    val background: Int,
    val rail: Int,
    val ink: Int,
    val muted: Int,
    val selected: Int,
    val selectedInk: Int,
    val selectedStroke: Int?,
    val settings: Int,
    val settingsInk: Int,
    val settingsStroke: Int?,
) {
    internal companion object {
        // #101722 gives AA contrast on the light mat selection used by day; night uses its own amber outline.
        private const val ACCESSIBLE_RAIL_INK = -15722718

        fun forNight(night: Boolean): Palette {
            val theme = TouchPalette.forNight(night)
            return Palette(
                background = theme.wall, rail = theme.moulding, ink = theme.mouldingInk, muted = theme.mouldingMuted,
                selected = theme.railSelected,
                selectedInk = if (night) theme.railSelectedInk else ACCESSIBLE_RAIL_INK,
                selectedStroke = if (night) theme.railSelectedStroke else null,
                settings = theme.moulding, settingsInk = theme.mouldingInk, settingsStroke = theme.mouldingLine,
            )
        }

        /** Returns the WCAG contrast ratio for opaque sRGB colors. */
        fun contrastRatio(foreground: Int, background: Int): Double {
            fun luminance(color: Int): Double {
                fun linear(channel: Int): Double {
                    val value = channel / 255.0
                    return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
                }
                return 0.2126 * linear((color ushr 16) and 0xFF) +
                    0.7152 * linear((color ushr 8) and 0xFF) +
                    0.0722 * linear(color and 0xFF)
            }
            val first = luminance(foreground)
            val second = luminance(background)
            return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
        }
    }
}
