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
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.wyattfleming.touchframe.R
import kotlin.math.abs
import kotlin.math.pow

/**
 * Owns only persistent shell chrome. The caller owns each destination's content and lifecycle.
 * Media destinations keep the content frame edge-to-edge until a deliberate left-edge gesture
 * reveals this navigation menu.
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
    private val railViewport = ScrollView(context).apply {
        isFillViewport = true
    }
    private val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
        addView(railViewport, LayoutParams(dp(80), LayoutParams.MATCH_PARENT, Gravity.START))
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
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        railViewport.addView(rail, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        setBackgroundColor(palette.background)
        railViewport.setBackgroundColor(palette.rail)
        rebuildRail(palette)

        val railWidth = dp(railWidthDpFor(resources.configuration.screenWidthDp))
        railViewport.layoutParams = (railViewport.layoutParams as LayoutParams).apply { width = railWidth }
        railViewport.visibility = if (menuVisible) View.VISIBLE else View.GONE
        scrim.visibility = if (destination.isImmersiveMedia && menuVisible) View.VISIBLE else View.GONE
        (scrim.getChildAt(0)?.layoutParams as? LayoutParams)?.leftMargin = railWidth + dp(48)
        (scrim.getChildAt(0) as? TextView)?.setTextColor(if (night) TouchPalette.NIGHT.ink else 0xFFF6F4EF.toInt())
        content.layoutParams = (content.layoutParams as LayoutParams).apply {
            leftMargin = if (destination.isImmersiveMedia) 0 else railWidth
            // A docked notice takes a strip under native content instead of covering its controls.
            bottomMargin = if (noticeVisible && !destination.isImmersiveMedia) noticeStripHeight() else 0
        }
        content.requestLayout()
        positionOverlays()
    }

    /** Docks notices at the start of the content area, inside the strip reserved by [applyChrome]. */
    private fun positionOverlays() {
        val railWidth = if (destination.isImmersiveMedia) 0 else dp(railWidthDpFor(resources.configuration.screenWidthDp))
        val scale = TouchScale.forContentHeightDp(height / resources.displayMetrics.density)
        noticeHost.layoutParams = (noticeHost.layoutParams as LayoutParams).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = railWidth + dp(scale.pad)
            bottomMargin = dp(scale.pad / 2)
        }
    }

    private fun noticeStripHeight(): Int {
        val scale = TouchScale.forContentHeightDp(height / resources.displayMetrics.density)
        return dp(scale.target + 16 + scale.pad / 2)
    }

    private fun rebuildRail(palette: Palette) {
        rail.removeAllViews()
        destinations.forEach { item -> rail.addView(destinationButton(item, palette)) }
        rail.addView(Space(context), LinearLayout.LayoutParams(0, 0, 1f))
        rail.addView(settingsButton(palette))
    }

    private fun destinationButton(item: TouchDestination, palette: Palette): View {
        val selected = item == destination
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = item.label
            minimumHeight = dp(MIN_TARGET_DP)
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = if (selected) rounded(palette.selected, palette.selectedStroke) else rounded(Color.TRANSPARENT, null)
            setOnClickListener {
                hideMenu()
                onNavigate(item)
            }
            addView(icon(item.icon, if (selected) palette.selectedInk else palette.muted))
            addView(label(item.label, if (selected) palette.selectedInk else palette.ink))
        }.also { view ->
            view.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(RAIL_BUTTON_HEIGHT_DP)).apply {
                setMargins(dp(RAIL_BUTTON_HORIZONTAL_MARGIN_DP), dp(RAIL_BUTTON_TOP_MARGIN_DP), dp(RAIL_BUTTON_HORIZONTAL_MARGIN_DP), 0)
            }
        }
    }

    private fun settingsButton(palette: Palette): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = "Settings"
        minimumHeight = dp(MIN_TARGET_DP)
        setPadding(dp(4), dp(5), dp(4), dp(5))
        background = rounded(palette.settings, palette.settingsStroke)
        setOnClickListener { onSettings() }
        addView(icon(R.drawable.ic_touch_settings, palette.settingsInk))
        addView(label("Settings", palette.settingsInk))
    }.also { view ->
        view.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(RAIL_BUTTON_HEIGHT_DP)).apply {
            setMargins(
                dp(RAIL_BUTTON_HORIZONTAL_MARGIN_DP),
                dp(RAIL_BUTTON_TOP_MARGIN_DP),
                dp(RAIL_BUTTON_HORIZONTAL_MARGIN_DP),
                dp(SETTINGS_BOTTOM_MARGIN_DP),
            )
        }
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

    private fun icon(resource: Int, color: Int): ImageView = ImageView(context).apply {
        setImageDrawable(context.getDrawable(resource)?.mutate()?.apply { setTint(color) })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(RAIL_ICON_DP), dp(RAIL_ICON_DP)).apply { bottomMargin = dp(4) }
    }

    /**
     * Single-line label in one weight, so selection never changes its width; it shrinks (never
     * truncates) only when an enlarged system font scale would not fit the pinned rail width.
     */
    private fun label(value: String, color: Int): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_MEDIUM)
        includeFontPadding = false
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        maxLines = 1
        val railDp = railWidthDpFor(resources.configuration.screenWidthDp)
        val available = dp(railDp - 2 * RAIL_BUTTON_HORIZONTAL_MARGIN_DP - 8).toFloat()
        var size = if (railDp >= 96) 18f else 15f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        while (paint.measureText(value) > available && size > MIN_RAIL_LABEL_SP) {
            size -= 0.5f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
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
        const val RAIL_BUTTON_HEIGHT_DP = 68
        private const val RAIL_BUTTON_HORIZONTAL_MARGIN_DP = 2
        const val RAIL_BUTTON_TOP_MARGIN_DP = 6
        const val SETTINGS_BOTTOM_MARGIN_DP = 8
        private const val RAIL_ICON_DP = 28
        private const val MIN_RAIL_LABEL_SP = 11f
        private const val INFO_NOTICE_MILLIS = 6_000L
        private const val ERROR_NOTICE_MILLIS = 30_000L
        private const val EDGE_HINT_MILLIS = 4_000L

        fun railWidthDpFor(screenWidthDp: Int): Int = if (screenWidthDp >= 1400) 96 else 80

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
