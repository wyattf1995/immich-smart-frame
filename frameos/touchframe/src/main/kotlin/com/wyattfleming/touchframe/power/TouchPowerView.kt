package com.wyattfleming.touchframe.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.wyattfleming.touchframe.ui.TouchFonts
import com.wyattfleming.touchframe.ui.TouchKit
import com.wyattfleming.touchframe.ui.TouchPalette
import com.wyattfleming.touchframe.ui.TouchScale
import java.time.ZoneId
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** The Power page's own field and dial colours; text uses Touch's day and night inks. */
internal data class TouchPowerColors(
    val field: Int,
    val face: Int,
    val rim: Int,
    val ink: Int,
    val muted: Int,
    val rule: Int,
    val track: Int,
    val pinLow: Int,
    val pinMid: Int,
    val pinHigh: Int,
    val use: Int,
    val daylight: Int,
) {
    companion object {
        /** Galvanized steel and copper by day. */
        val DAY = TouchPowerColors(
            field = 0xFFDCE2E6.toInt(), face = 0xFFEEF1F2.toInt(), rim = 0xFFB9C3CA.toInt(), ink = 0xFF16212B.toInt(),
            muted = 0xFF4F5D69.toInt(), rule = 0xFFC3CCD2.toInt(), track = 0xFFCBD3D9.toInt(), pinLow = 0xFF8C9AA6.toInt(),
            pinMid = 0xFFC0703A.toInt(), pinHigh = 0xFF9B3A1E.toInt(), use = 0xFF2F4A5C.toInt(), daylight = 0xFFD2AE55.toInt(),
        )

        /** Touch's bedside night: the dial pulled toward black, text in the night amber. */
        val NIGHT = TouchPowerColors(
            field = 0xFF0A0907.toInt(), face = 0xFF14110D.toInt(), rim = 0xFF2A241B.toInt(), ink = TouchPalette.NIGHT.ink,
            muted = TouchPalette.NIGHT.inkMuted, rule = 0xFF2A241B.toInt(), track = 0xFF1E1A14.toInt(), pinLow = 0xFF3E372D.toInt(),
            pinMid = 0xFF7A5228.toInt(), pinHigh = 0xFF8A3A1E.toInt(), use = 0xFF8E7A58.toInt(), daylight = 0xFF5A4520.toInt(),
        )

        fun forNight(night: Boolean) = if (night) NIGHT else DAY
    }
}

/**
 * Electricity on one screen: a 24-hour plug-in-timer dial (now under the fixed pointer, coming hours clockwise, pins
 * taller as the price rises, the last day's metered use inside) and, beside it, the period now and next, what is
 * running and what it costs an hour, laundry, today's metered total and solar. Nothing animates; the dial turns
 * with the clock once a minute.
 */
@SuppressLint("ViewConstructor")
internal class TouchPowerView(
    context: Context,
    private val onRetry: () -> Unit,
    private val onSignIn: () -> Unit,
) : FrameLayout(context) {
    private val dial = TouchPowerDialView(context)
    private val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val legend = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var state = TouchPowerViewState(loading = true)
    private var night = false
    private var renderedKey: Any? = null
    private var tickSlot = -1L

    init {
        addView(dial)
        addView(side)
        addView(legend)
    }

    fun render(next: TouchPowerViewState) {
        state = next
        rebuild()
    }

    fun setNight(value: Boolean) {
        if (night == value) return
        night = value
        rebuild()
    }

    /** Called every second by the activity clock; the page is re-derived once a minute at most. */
    fun tick() {
        val slot = System.currentTimeMillis() / SLOT_MILLIS
        if (slot == tickSlot) return
        tickSlot = slot
        rebuild()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        renderedKey = null
        rebuild()
    }

    private fun rebuild() {
        val now = System.currentTimeMillis() / SLOT_MILLIS * SLOT_MILLIS
        val scale = TouchScale.forScreen(resources.configuration)
        val wall = scale == TouchScale.WALL
        val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
        val snapshot = state.snapshot
        val screen = snapshot?.let {
            TouchPowerPresenter.present(it, state.connection, now, ZoneId.systemDefault(), twentyFour, wall, Locale.getDefault())
        }
        val key = listOf(screen, night, scale, state.connection, state.loading, snapshot == null)
        if (key == renderedKey) return
        renderedKey = key

        val colors = TouchPowerColors.forNight(night)
        val kit = TouchKit(context, TouchPalette.forNight(night), scale)
        setBackgroundColor(colors.field)
        val pad = kit.dp(scale.pad)
        val dialWidth = kit.dp(if (wall) DIAL_WIDTH_WALL_DP else DIAL_WIDTH_PANEL_DP)
        dial.layoutParams = LayoutParams(dialWidth, LayoutParams.MATCH_PARENT)
        side.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dialWidth + kit.dp(if (wall) 20 else 4)
            rightMargin = pad
            topMargin = pad
            bottomMargin = (pad * 0.6f).toInt()
        }
        legend.layoutParams = LayoutParams(dialWidth - pad, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = pad
            bottomMargin = (pad * 0.4f).toInt()
        }
        side.removeAllViews()
        legend.removeAllViews()

        if (screen == null) {
            dial.set(null, colors, wall)
            emptyState(kit, colors)
            return
        }
        dial.set(screen.dial, colors, wall)
        dial.contentDescription = listOfNotNull(screen.dial.hubPrice?.let { "$it a kilowatt hour now" }, screen.headline, screen.next).joinToString(". ")
        sideColumn(kit, colors, screen, wall)
        screen.legend.forEach { line ->
            legend.addView(kit.text(line, scale.caption, color = colors.muted).apply { setLineSpacing(0f, 1.25f) }, wrap())
        }
    }

    private fun sideColumn(kit: TouchKit, colors: TouchPowerColors, screen: TouchPowerScreen, wall: Boolean) {
        val scale = kit.scale
        side.addView(kit.text(screen.headline, scale.display * 0.8f, TouchFonts.Face.DISPLAY_BOLD, colors.ink, maxLines = 1), wrap())
        screen.next?.let { side.addView(kit.text(it, scale.body, color = colors.muted, maxLines = 1), wrap(top = 10)) }

        // Running now: a title and the total across the full width, then one row per device.
        side.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(kit.text(screen.runningTitle, scale.headline, TouchFonts.Face.TEXT_BOLD, colors.ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            val summary = screen.runningSummary ?: screen.warning?.takeIf { wall }
            summary?.let {
                addView(kit.text(it, scale.body, if (screen.runningSummary == null) TouchFonts.Face.TEXT_MEDIUM else TouchFonts.Face.TEXT,
                    if (screen.runningSummary == null) colors.ink else colors.muted, maxLines = 1).apply { fontFeatureSettings = "tnum" },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(16) })
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp((scale.pad * 0.9f).toInt()) })
        side.addView(rule(colors, 2), LinearLayout.LayoutParams(MATCH, kit.dp(2)).apply { topMargin = kit.dp(10) })
        val rowHeight = kit.dp(if (wall) 50 else 36)
        screen.rows.forEach { row -> side.addView(deviceRow(kit, colors, row, wall), LinearLayout.LayoutParams(MATCH, rowHeight)) }
        side.addView(kit.text(screen.note, if (screen.warning != null && !wall) scale.label else scale.caption,
            if (screen.warning != null && !wall) TouchFonts.Face.TEXT_MEDIUM else TouchFonts.Face.TEXT,
            if (screen.warning != null && !wall) colors.ink else colors.muted, maxLines = 2).apply { setLineSpacing(0f, 1.25f) }, wrap(top = 10))

        if (screen.laundry.isNotEmpty()) {
            side.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                screen.laundry.forEachIndexed { index, line ->
                    val bold = index == 0 && screen.laundryRunning
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(LaundryIcon(context, colors.muted), LinearLayout.LayoutParams(kit.dp(if (wall) 30 else 26), kit.dp(if (wall) 30 else 26)))
                        addView(kit.text(line, scale.body, if (bold) TouchFonts.Face.TEXT_BOLD else TouchFonts.Face.TEXT, colors.ink, maxLines = 1),
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(12) })
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (index > 0) topMargin = kit.dp(6) })
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp((scale.pad * 0.6f).toInt()) })
        }

        side.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
        side.addView(rule(colors, 2), LinearLayout.LayoutParams(MATCH, kit.dp(2)))
        side.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(footCell(kit, colors, "Metered plugs today", screen.todayKwh, listOf(screen.todayCaption), false), LinearLayout.LayoutParams(0, WRAP, 1f))
            screen.solar?.let { solar ->
                addView(footCell(kit, colors, solar.label, solar.value, solar.lines, solar.stale),
                    LinearLayout.LayoutParams(0, WRAP, 1.25f).apply { marginStart = kit.dp(if (wall) scale.pad else 20) })
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = kit.dp(16) })
    }

    private fun deviceRow(kit: TouchKit, colors: TouchPowerColors, row: TouchPowerRow, wall: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "${row.name}, ${row.value}"
        background = RowRule(colors.rule, kit.dp(1))
        val scale = kit.scale
        val quiet = row.tone != TouchPowerRow.Tone.RUNNING
        addView(kit.text(row.name, scale.body, color = if (quiet) colors.muted else colors.ink, maxLines = 1).apply {
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(kit.dp(if (wall) 330 else 236), WRAP))
        if (row.tone == TouchPowerRow.Tone.MISSING) {
            addView(kit.text(row.value, scale.label, color = colors.muted, maxLines = 1).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        } else {
            addView(UsageBar(context, row.fraction, colors.track, colors.ink), LinearLayout.LayoutParams(0, kit.dp(if (wall) 10 else 8), 1f).apply {
                marginStart = kit.dp(if (wall) 20 else 14)
            })
            addView(kit.text(row.value, scale.body, if (quiet) TouchFonts.Face.TEXT else TouchFonts.Face.DISPLAY_BOLD, if (quiet) colors.muted else colors.ink, maxLines = 1).apply {
                gravity = Gravity.END
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(kit.dp(if (wall) 150 else 110), WRAP).apply { marginStart = kit.dp(if (wall) 20 else 14) })
        }
    }

    private fun footCell(kit: TouchKit, colors: TouchPowerColors, label: String, value: String?, lines: List<String>, stale: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val scale = kit.scale
            addView(kit.text(label, scale.label, color = colors.muted, maxLines = 1), wrap(top = 14))
            if (stale) {
                lines.firstOrNull()?.let { addView(kit.text(it, scale.body, color = colors.ink, maxLines = 2).apply { setLineSpacing(0f, 1.2f) }, wrap(top = 4)) }
                lines.drop(1).forEach { addView(kit.text(it, scale.caption, color = colors.muted, maxLines = 1), wrap(top = 2)) }
                return@apply
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(kit.text(value ?: "–", scale.title, TouchFonts.Face.DISPLAY_BOLD, colors.ink, maxLines = 1).apply { fontFeatureSettings = "tnum" },
                    LinearLayout.LayoutParams(WRAP, WRAP))
                addView(kit.text("kWh", scale.body, color = colors.ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = kit.dp(8)
                    bottomMargin = kit.dp(3)
                })
            }, wrap(top = 2))
            lines.forEach { addView(kit.text(it, scale.caption, color = colors.muted, maxLines = 1), wrap(top = 2)) }
        }

    private fun emptyState(kit: TouchKit, colors: TouchPowerColors) {
        val scale = kit.scale
        val (title, body, action) = when {
            state.connection == TouchPowerConnection.SIGN_IN_REQUIRED ->
                Triple("Sign in to see power use", "Home Assistant needs this frame to sign in again.", "Sign in" to onSignIn)
            state.connection == TouchPowerConnection.NOT_SET_UP ->
                Triple("Power isn’t set up in Home Assistant", "Home Assistant has no Frame power sensor yet. Add the Power package, then try again.", "Try again" to onRetry)
            state.loading -> Triple("Getting power readings…", null, null)
            else -> Triple("Home Assistant isn’t responding", "Check the frame’s Wi‑Fi, then try again.", "Try again" to onRetry)
        }
        side.addView(kit.text(title, scale.headline, TouchFonts.Face.TEXT_MEDIUM, colors.ink), wrap())
        body?.let { side.addView(kit.text(it, scale.body, color = colors.muted), wrap(top = 10)) }
        action?.let { (label, run) ->
            side.addView(kit.button(label, TouchKit.Emphasis.SECONDARY) { run() }, wrap(top = 24))
        }
    }

    private fun rule(colors: TouchPowerColors, heightDp: Int): View = View(context).apply { setBackgroundColor(colors.rule) }.also { it.minimumHeight = heightDp }

    private fun wrap(top: Int = 0) = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = (top * resources.displayMetrics.density + 0.5f).toInt() }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        const val SLOT_MILLIS = 60_000L
        const val DIAL_WIDTH_WALL_DP = 1040
        const val DIAL_WIDTH_PANEL_DP = 700
    }
}

/** A one-pixel rule along a row's bottom edge. */
private class RowRule(color: Int, private val heightPx: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint().apply { this.color = color }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds.left.toFloat(), (bounds.bottom - heightPx).toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Required by Drawable on API 23", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** A rounded track with the device's share of the busiest load filled in; no track at all under ten watts. */
@SuppressLint("ViewConstructor")
private class UsageBar(context: Context, private val fraction: Float?, private val trackColor: Int, private val fillColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val value = fraction ?: return
        val radius = height / 2f
        paint.color = trackColor
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = fillColor
        rect.set(0f, 0f, maxOf(height.toFloat(), width * value), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}

/** The washer glyph from the design icon set, drawn as strokes so it takes the row's colour. */
@SuppressLint("ViewConstructor")
private class LaundryIcon(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val unit = minOf(width, height) / 24f
        paint.strokeWidth = 1.8f * unit
        canvas.drawRect(4.5f * unit, 3f * unit, 19.5f * unit, 21f * unit, paint)
        canvas.drawLine(4.5f * unit, 7.5f * unit, 19.5f * unit, 7.5f * unit, paint)
        canvas.drawCircle(12f * unit, 13.75f * unit, 4f * unit, paint)
        canvas.drawPoint(7.5f * unit, 5.25f * unit, paint)
        canvas.drawPoint(10f * unit, 5.25f * unit, paint)
    }
}

/**
 * The timer dial. Angles run clockwise from the top in hours from now (24 hours to a turn), so the pointer at the
 * top is always now. Paths and positions are rebuilt only when the data or size changes, never while drawing.
 */
@SuppressLint("ViewConstructor")
internal class TouchPowerDialView(context: Context) : View(context) {
    private var dial: TouchPowerDial? = null
    private var colors = TouchPowerColors.DAY
    private var wall = true
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val daylightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hubCaptionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pins = ArrayList<FloatArray>()
    private val pinColors = ArrayList<Int>()
    private val labels = ArrayList<Triple<Float, Float, String>>()
    private val wedges = ArrayList<Pair<Path, Int>>()
    private val daylight = Path()
    private val pointer = Path()
    private var cx = 0f
    private var cy = 0f
    private var faceRadius = 0f
    private var dashRadius = 0f
    private var pointerTop = 0f

    fun set(next: TouchPowerDial?, nextColors: TouchPowerColors, isWall: Boolean) {
        dial = next
        colors = nextColors
        wall = isWall
        rebuild()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuild()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun x(hours: Double, radius: Float) = cx + radius * sin(hours / 24.0 * 2 * Math.PI).toFloat()
    private fun y(hours: Double, radius: Float) = cy - radius * cos(hours / 24.0 * 2 * Math.PI).toFloat()

    private fun rebuild() {
        pins.clear(); pinColors.clear(); labels.clear(); wedges.clear(); daylight.reset(); pointer.reset()
        if (width <= 0 || height <= 0) return
        val s = if (wall) 1f else PANEL_SCALE
        cx = dp(if (wall) 520f else 344f)
        cy = dp(if (wall) 510f else 358f)
        val dayRadius = dp(402f * s)
        faceRadius = dp(384f * s)
        val pinBase = dp(334f * s)
        val labelRadius = dp(296f * s)
        val useInner = dp(150f * s)
        val useSpan = dp(112f * s)

        facePaint.color = colors.face
        rimPaint.color = colors.rim
        rimPaint.strokeWidth = dp(2f * s)
        val data = dial
        pointerTop = cy - dayRadius - dp(12f * s)
        pointer.moveTo(cx - dp(16f * s), pointerTop - dp(22f * s))
        pointer.lineTo(cx + dp(16f * s), pointerTop - dp(22f * s))
        pointer.lineTo(cx, pointerTop + dp(4f * s))
        pointer.close()
        pointerPaint.color = colors.ink
        if (data == null) {
            invalidate()
            return
        }

        data.daylight?.let { (from, to) ->
            val oval = RectF(cx - dayRadius, cy - dayRadius, cx + dayRadius, cy + dayRadius)
            daylight.arcTo(oval, (from / 24.0 * 360.0 - 90.0).toFloat(), ((to - from) / 24.0 * 360.0).toFloat(), true)
        }
        daylightPaint.color = colors.daylight
        daylightPaint.strokeWidth = dp(6f * s)

        // Pins: the cheapest price is the shortest and greyest, the dearest the tallest and deepest copper.
        val top = (data.ranks - 1).coerceAtLeast(1)
        pinPaint.strokeWidth = (2 * Math.PI * pinBase / 96 * 0.56).toFloat()
        data.pins.forEach { pin ->
            val rank = pin.rank.coerceIn(0, top)
            val length = dp((16f + rank * 30f / top) * s)
            pins.add(floatArrayOf(x(pin.hoursAhead, pinBase), y(pin.hoursAhead, pinBase), x(pin.hoursAhead, pinBase + length), y(pin.hoursAhead, pinBase + length)))
            pinColors.add(when {
                data.ranks <= 1 || rank == 0 -> colors.pinLow
                rank == top -> colors.pinHigh
                else -> colors.pinMid
            })
        }

        labelPaint.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_MEDIUM)
        labelPaint.textSize = sp(if (wall) 22f else 18f)
        labelPaint.color = colors.muted
        val labelOffset = (labelPaint.descent() + labelPaint.ascent()) / 2
        data.hourLabels.forEach { (hours, text) -> labels.add(Triple(x(hours, labelRadius), y(hours, labelRadius) - labelOffset, text)) }

        // The use ring: one wedge per hour, darkest beside the pointer and fading back through the day.
        val maxK = data.ringMaxKwh
        val night = colors == TouchPowerColors.NIGHT
        val newest = if (night) 0.7f else 0.9f
        data.wedges.forEach { wedge ->
            val from = -wedge.startHoursAgo
            val to = -wedge.endHoursAgo
            if (to - from < 0.05) return@forEach
            val gap = 0.03
            val outer = useInner + (wedge.kwh / maxK).coerceIn(0.0, 1.0).toFloat() * useSpan
            val startDeg = ((from + gap) / 24.0 * 360.0 - 90.0).toFloat()
            val sweep = ((to - from - 2 * gap) / 24.0 * 360.0).toFloat()
            val path = Path()
            path.arcTo(RectF(cx - outer, cy - outer, cx + outer, cy + outer), startDeg, sweep, true)
            path.arcTo(RectF(cx - useInner, cy - useInner, cx + useInner, cy + useInner), startDeg + sweep, -sweep)
            path.close()
            val age = ((wedge.startHoursAgo + wedge.endHoursAgo) / 2).coerceIn(0.0, 24.0)
            val alpha = newest - (newest - 0.25f) * (age / 24.0).toFloat()
            wedges.add(path to ((alpha * 255).toInt().coerceIn(0, 255) shl 24 or (colors.use and 0xFFFFFF)))
        }
        dashRadius = useInner + (TouchPowerPresenter.RING_STEP_KWH / maxK).toFloat() * useSpan
        dashPaint.color = colors.muted
        dashPaint.alpha = 180
        dashPaint.strokeWidth = dp(1.5f * s)
        dashPaint.pathEffect = DashPathEffect(floatArrayOf(dp(4f * s), dp(7f * s)), 0f)

        hubPaint.typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY_BOLD)
        hubPaint.textSize = sp(if (wall) 112f else 80f)
        hubPaint.color = colors.ink
        hubCaptionPaint.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT)
        hubCaptionPaint.textSize = sp(if (wall) 24f else 18f)
        hubCaptionPaint.color = colors.muted
        timePaint.typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY_BOLD)
        timePaint.textSize = sp(if (wall) 28f else 22f)
        timePaint.color = colors.ink
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0) return
        canvas.drawCircle(cx, cy, faceRadius, facePaint)
        canvas.drawCircle(cx, cy, faceRadius, rimPaint)
        val data = dial ?: return
        if (!daylight.isEmpty) canvas.drawPath(daylight, daylightPaint)
        pins.forEachIndexed { index, line ->
            pinPaint.color = pinColors[index]
            canvas.drawLine(line[0], line[1], line[2], line[3], pinPaint)
        }
        labels.forEach { (lx, ly, text) -> canvas.drawText(text, lx, ly, labelPaint) }
        wedges.forEach { (path, color) ->
            wedgePaint.color = color
            canvas.drawPath(path, wedgePaint)
        }
        canvas.drawCircle(cx, cy, dashRadius, dashPaint)
        canvas.drawPath(pointer, pointerPaint)
        canvas.drawText(data.pointerTime, cx, pointerTop - dp(if (wall) 34f else 24f), timePaint)
        data.hubPrice?.let { canvas.drawText(it, cx, cy + dp(if (wall) 26f else 18f), hubPaint) }
        canvas.drawText("a kWh now", cx, cy + dp(if (wall) 72f else 52f), hubCaptionPaint)
    }

    private companion object {
        /** The 1280 dial is the 1920 dial at this size. */
        const val PANEL_SCALE = 0.7125f
    }
}
