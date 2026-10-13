package com.wyattfleming.touchframe.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import kotlin.math.abs

/**
 * Owns only persistent shell chrome. The caller owns each destination's content and lifecycle.
 * Media destinations keep the content frame edge-to-edge until a deliberate left-edge gesture
 * reveals this navigation menu.
 */
class TouchShellLayout(
    context: Context,
    private val onNavigate: (TouchDestination) -> Unit,
    private val onSettings: () -> Unit,
) : FrameLayout(context) {
    val content: FrameLayout = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val scrim = View(context).apply {
        setBackgroundColor(Color.argb(145, 0, 0, 0))
        contentDescription = context.getString(com.wyattfleming.touchframe.R.string.return_to_navigation)
        isClickable = true
        setOnClickListener { hideMenu() }
    }
    private val rail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var destination = TouchDestination.HOME
    private var destinations: List<TouchDestination> = TouchDestinationOrder.defaults
    private var night = false
    private var menuVisible = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var trackingEdge = false

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rail, LayoutParams(dp(80), LayoutParams.MATCH_PARENT, Gravity.START))
        select(TouchDestination.HOME)
    }

    fun select(next: TouchDestination) {
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

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!destination.isImmersiveMedia || menuVisible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeDownX = event.x
                edgeDownY = event.y
                trackingEdge = edgeDownX <= dp(EDGE_WIDTH_DP)
            }
            MotionEvent.ACTION_MOVE -> if (trackingEdge && TouchShellGesture.shouldReveal(
                    destination,
                    edgeDownX,
                    edgeDownY,
                    event.x,
                    event.y,
                    dp(EDGE_WIDTH_DP),
                    dp(HORIZONTAL_THRESHOLD_DP),
                    dp(VERTICAL_TOLERANCE_DP),
                )
            ) {
                trackingEdge = false
                showMenu()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> trackingEdge = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = menuVisible || super.onTouchEvent(event)

    private fun applyChrome() {
        val palette = Palette.forNight(night)
        setBackgroundColor(palette.background)
        rail.setBackgroundColor(palette.rail)
        rebuildRail(palette)

        val railWidth = dp(railWidthDpFor(resources.configuration.screenWidthDp))
        rail.layoutParams = (rail.layoutParams as LayoutParams).apply { width = railWidth }
        rail.visibility = if (menuVisible) View.VISIBLE else View.GONE
        scrim.visibility = if (destination.isImmersiveMedia && menuVisible) View.VISIBLE else View.GONE
        content.layoutParams = (content.layoutParams as LayoutParams).apply {
            leftMargin = if (destination.isImmersiveMedia) 0 else railWidth
        }
        content.requestLayout()
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
            contentDescription = item.label
            minimumHeight = dp(MIN_TARGET_DP)
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = rounded(if (selected) palette.selected else Color.TRANSPARENT)
            setOnClickListener {
                hideMenu()
                onNavigate(item)
            }
            addView(label(item.glyph, 20f, if (selected) palette.selectedInk else palette.muted))
            addView(label(item.label, if (railWidthDpFor(resources.configuration.screenWidthDp) >= 96) 16f else 14f,
                if (selected) palette.selectedInk else palette.ink))
        }.also { view ->
            view.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(68)).apply {
                setMargins(dp(6), dp(6), dp(6), 0)
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
        background = rounded(palette.settings)
        setOnClickListener { onSettings() }
        addView(label("⚙", 20f, palette.ink))
        addView(label("Settings", if (railWidthDpFor(resources.configuration.screenWidthDp) >= 96) 16f else 14f, palette.ink))
    }.also { view ->
        view.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(68)).apply {
            setMargins(dp(6), dp(6), dp(6), dp(8))
        }
    }

    private fun label(value: String, sizeSp: Float, color: Int): TextView = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        maxLines = 1
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    internal companion object {
        const val EDGE_WIDTH_DP = 48
        const val HORIZONTAL_THRESHOLD_DP = 56
        const val VERTICAL_TOLERANCE_DP = 32
        const val MIN_TARGET_DP = 56

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

private data class Palette(
    val background: Int,
    val rail: Int,
    val ink: Int,
    val muted: Int,
    val selected: Int,
    val selectedInk: Int,
    val settings: Int,
) {
    companion object {
        fun forNight(night: Boolean): Palette = if (night) {
            Palette(0xFF101722.toInt(), 0xFF172536.toInt(), 0xFFF1EDE4.toInt(), 0xFFA9B2B8.toInt(), 0xFF4F9F9A.toInt(), 0xFF101722.toInt(), 0xFFD8C390.toInt())
        } else {
            Palette(0xFFF1EDE4.toInt(), 0xFFE3E6DF.toInt(), 0xFF182630.toInt(), 0xFF56646A.toInt(), 0xFF4F9F9A.toInt(), Color.WHITE, 0xFFD8C390.toInt())
        }
    }
}
