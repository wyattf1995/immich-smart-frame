package com.wyattfleming.touchframe.weather

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.util.LongSparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.wyattfleming.frameui.R
import com.wyattfleming.touchframe.ui.TouchFonts
import com.wyattfleming.touchframe.ui.TouchKit
import com.wyattfleming.touchframe.ui.TouchPalette
import com.wyattfleming.touchframe.ui.TouchScale
import java.time.ZoneId
import java.util.Locale

/**
 * Weather on one screen, read from the top down: the sky now (temperature, conditions, the next turn and any rain,
 * then wind, humidity, UV, sun and pressure), the next 24 hours as one temperature curve over day and night bands
 * with rain bars beneath, and the week as a band between each day's high and low. Nothing animates; the page is
 * re-derived when the forecast arrives and once every five minutes so "Now" and the night bands keep up.
 */
@SuppressLint("ViewConstructor")
class TouchWeatherView(
    context: Context,
    private val onRetry: () -> Unit,
    private val onSignIn: () -> Unit,
) : FrameLayout(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val ridge = TouchWeatherRidgeView(context)
    private val week = TouchWeatherWeekView(context)
    private val empty = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var state = TouchWeatherViewState(loading = true)
    private var night = false
    private var renderedKey: Any? = null
    private var tickSlot = -1L

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(next: TouchWeatherViewState) {
        state = next
        rebuild()
    }

    fun setNight(value: Boolean) {
        if (night == value) return
        night = value
        rebuild()
    }

    /** Called every second by the activity clock; the page is re-derived once per five-minute slot at most. */
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
        val screen = TouchWeatherPresenter.present(state, now, ZoneId.systemDefault(), twentyFour, wall, Locale.getDefault())
        // The empty page shows the host's words, so a new message with the same connection must redraw it too.
        val words = if (screen == null) state.message to state.detail else null
        val key = listOf(screen, night, scale, state.connection, state.loading, state.snapshot == null, words)
        if (key == renderedKey) return
        renderedKey = key

        val colors = TouchWeatherColors.forNight(night)
        val kit = TouchKit(context, TouchPalette.forNight(night), scale)
        setBackgroundColor(colors.field)
        content.removeAllViews()
        empty.removeAllViews()
        if (screen == null) {
            content.visibility = GONE
            empty.visibility = VISIBLE
            emptyState(kit, colors)
            return
        }
        content.visibility = VISIBLE
        empty.visibility = GONE
        content.addView(sky(kit, colors, screen, wall), LinearLayout.LayoutParams(MATCH, WRAP))
        ridge.set(screen, colors, night, wall)
        ridge.contentDescription = screen.hoursDescription
        content.addView(ridge, LinearLayout.LayoutParams(MATCH, 0, 1f))
        week.set(screen, colors, night, wall)
        week.contentDescription = screen.weekDescription
        week.visibility = if (screen.days.isEmpty()) GONE else VISIBLE
        content.addView(week, LinearLayout.LayoutParams(MATCH, kit.dp(if (wall) WEEK_HEIGHT_WALL_DP else WEEK_HEIGHT_PANEL_DP)))
    }

    /** What it is like now: glyph, temperature and words on the left, the facts grid on the right. */
    private fun sky(kit: TouchKit, colors: TouchWeatherColors, screen: TouchWeatherScreen, wall: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(colors.skyTop, colors.skyBottom))
        val scale = kit.scale
        val pad = kit.dp(scale.pad)
        setPadding(pad, (pad * 0.55f).toInt(), pad, (pad * 0.35f).toInt())

        screen.glyph?.let { glyph ->
            addView(TouchWeatherGlyphView(context, glyph, colors), LinearLayout.LayoutParams(kit.dp((scale.clock * 0.78f).toInt()), kit.dp((scale.clock * 0.78f).toInt())).apply {
                marginEnd = (pad * 0.6f).toInt()
            })
        }
        addView(temperature(kit, colors, screen.temperature), LinearLayout.LayoutParams(WRAP, WRAP))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            screen.condition?.let { addView(kit.text(it, scale.title, TouchFonts.Face.DISPLAY_BOLD, colors.ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP)) }
            if (screen.detail.isNotEmpty()) {
                val medium = TouchFonts.get(context, TouchFonts.Face.TEXT_MEDIUM)
                val line = SpannableStringBuilder()
                screen.detail.forEach { span ->
                    val start = line.length
                    line.append(span.text)
                    if (span.bold) {
                        line.setSpan(FaceSpan(medium), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        line.setSpan(ForegroundColorSpan(colors.ink), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                addView(kit.text(line, scale.body, color = colors.muted, maxLines = 1).apply { fontFeatureSettings = "tnum" },
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(8) })
            }
            screen.rain?.let { addView(kit.text(it, scale.body, TouchFonts.Face.TEXT_BOLD, colors.rain, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(6) }) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = (pad * 0.8f).toInt() })
        addView(facts(kit, colors, screen, wall), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = pad })
    }

    /** The big numeral with a raised degree mark, top-aligned to the digits rather than the line box. */
    private fun temperature(kit: TouchKit, colors: TouchWeatherColors, value: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        contentDescription = "$value degrees"
        val number = kit.text(value, kit.scale.clock, TouchFonts.Face.DISPLAY, colors.ink).apply {
            fontFeatureSettings = "tnum"
            letterSpacing = -0.02f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val degree = kit.text("°", kit.scale.clock * 0.42f, TouchFonts.Face.DISPLAY, colors.ink).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val (numberTop, numberBottom) = inkInsets(number)
        addView(number, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = -numberTop
            bottomMargin = -numberBottom
        })
        addView(degree, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = -inkInsets(degree).first })
    }

    /** Up to six facts in three columns, the status under them on the right. */
    private fun facts(kit: TouchKit, colors: TouchWeatherColors, screen: TouchWeatherScreen, wall: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        val scale = kit.scale
        val gap = kit.dp((scale.pad * 1.1f).toInt())
        addView(TableLayout(context).apply {
            screen.facts.chunked(3).forEachIndexed { rowIndex, row ->
                addView(TableRow(context).apply {
                    row.forEachIndexed { index, fact ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            contentDescription = "${fact.label}, ${fact.value}"
                            addView(kit.text(fact.label, scale.caption, color = colors.muted, maxLines = 1).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },
                                LinearLayout.LayoutParams(WRAP, WRAP))
                            addView(kit.text(fact.value, scale.body + 2f, TouchFonts.Face.DISPLAY, colors.ink, maxLines = 1).apply {
                                fontFeatureSettings = "tnum"
                                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(2) })
                        }, TableRow.LayoutParams(WRAP, WRAP).apply {
                            if (index > 0) marginStart = gap
                            if (rowIndex > 0) topMargin = kit.dp((scale.pad * 0.35f).toInt())
                        })
                    }
                })
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        if (state.connection == TouchWeatherConnection.SIGN_IN_REQUIRED) {
            addView(kit.text(screen.status, scale.caption, color = colors.muted, maxLines = 2).apply {
                gravity = Gravity.END
                maxWidth = kit.dp(if (wall) 620 else 420)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) })
            addView(kit.button("Sign in", TouchKit.Emphasis.SECONDARY, sizeSp = scale.caption) { onSignIn() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(8) })
        } else if (screen.status.isNotEmpty()) {
            addView(kit.text(screen.status, scale.caption, color = colors.muted, maxLines = 2).apply {
                gravity = Gravity.END
                maxWidth = kit.dp(if (wall) 620 else 420)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) })
        }
    }

    private fun emptyState(kit: TouchKit, colors: TouchWeatherColors) {
        val scale = kit.scale
        val (title, body, action) = when {
            state.connection == TouchWeatherConnection.NOT_SET_UP ->
                Triple("Weather isn’t set up on this frame", "This frame needs its Home Assistant connection settings first.", null)
            state.connection == TouchWeatherConnection.SIGN_IN_REQUIRED ->
                Triple("Sign in to see the weather", "Home Assistant needs this frame to sign in again.", "Sign in" to onSignIn)
            state.loading -> Triple("Getting the forecast…", null, null)
            else -> Triple("Home Assistant isn’t responding", "Check the frame’s Wi‑Fi, then try again.", "Try again" to onRetry)
        }
        val pad = kit.dp(scale.pad)
        empty.setPadding(pad, pad, pad, pad)
        empty.addView(kit.text(state.message ?: title, scale.headline, TouchFonts.Face.TEXT_MEDIUM, colors.ink), LinearLayout.LayoutParams(WRAP, WRAP))
        (state.detail ?: body)?.let { empty.addView(kit.text(it, scale.body, color = colors.muted), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(10) }) }
        action?.let { (label, run) ->
            empty.addView(kit.button(label, TouchKit.Emphasis.SECONDARY) { run() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(24) })
        }
    }

    /** Blank space above and below [view]'s glyphs: a 104sp numeral's line box is more than a third empty. */
    private fun inkInsets(view: TextView): Pair<Int, Int> {
        val metrics = view.paint.fontMetricsInt
        val ink = Rect().also { view.paint.getTextBounds(view.text.toString(), 0, view.text.length, it) }
        return (ink.top - metrics.ascent).coerceAtLeast(0) to (metrics.descent - ink.bottom).coerceAtLeast(0)
    }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        /** "Now" and the night bands move with the clock; nothing else changes between forecasts. */
        const val SLOT_MILLIS = 5 * 60_000L
        const val WEEK_HEIGHT_PANEL_DP = 232
        const val WEEK_HEIGHT_WALL_DP = 318
    }
}

/** Sets a bundled face on part of a line (TypefaceSpan only takes a Typeface from API 28). */
private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = face
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = face
    }
}

/** Draws a weather glyph: the line in the icon ink, a sun's disc filled in the sun colour underneath it. */
class TouchWeatherGlyphs(private val context: Context) {
    /** Tinted drawables keyed by resource and colour, so drawing a page allocates nothing. */
    private val cache = LongSparseArray<Drawable>()

    fun draw(canvas: Canvas, glyph: TouchWeatherGlyph, left: Float, top: Float, size: Float, colors: TouchWeatherColors) {
        val l = left.toInt()
        val t = top.toInt()
        val s = size.toInt()
        disc(glyph).takeIf { it != 0 }?.let { drawable(it, colors.sun)?.apply { setBounds(l, t, l + s, t + s); draw(canvas) } }
        drawable(line(glyph), colors.icon)?.apply { setBounds(l, t, l + s, t + s); draw(canvas) }
    }

    private fun drawable(resource: Int, color: Int): Drawable? {
        val key = (resource.toLong() shl 32) or (color.toLong() and 0xFFFFFFFFL)
        cache.get(key)?.let { return it }
        return context.getDrawable(resource)?.mutate()?.apply { setTint(color) }?.also { cache.put(key, it) }
    }

    private fun line(glyph: TouchWeatherGlyph): Int = when (glyph) {
        TouchWeatherGlyph.SUN -> R.drawable.ic_wx_sun
        TouchWeatherGlyph.MOON -> R.drawable.ic_wx_moon
        TouchWeatherGlyph.PART_SUN -> R.drawable.ic_wx_part_sun
        TouchWeatherGlyph.PART_MOON -> R.drawable.ic_wx_part_moon
        TouchWeatherGlyph.CLOUD -> R.drawable.ic_wx_cloud
        TouchWeatherGlyph.FOG -> R.drawable.ic_wx_fog
        TouchWeatherGlyph.RAIN -> R.drawable.ic_wx_rain
        TouchWeatherGlyph.SNOW -> R.drawable.ic_wx_snow
        TouchWeatherGlyph.STORM -> R.drawable.ic_wx_storm
        TouchWeatherGlyph.WIND -> R.drawable.ic_wx_wind
    }

    /** The filled disc drawn under a sun glyph, or 0. */
    private fun disc(glyph: TouchWeatherGlyph): Int = when (glyph) {
        TouchWeatherGlyph.SUN -> R.drawable.ic_wx_sun_disc
        TouchWeatherGlyph.PART_SUN -> R.drawable.ic_wx_part_sun_disc
        else -> 0
    }
}

/** The glyph beside the temperature now. */
@SuppressLint("ViewConstructor")
private class TouchWeatherGlyphView(context: Context, private val glyph: TouchWeatherGlyph, private val colors: TouchWeatherColors) : View(context) {
    private val glyphs = TouchWeatherGlyphs(context)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        glyphs.draw(canvas, glyph, 0f, 0f, minOf(width, height).toFloat(), colors)
    }
}

/**
 * The next 24 hours as a ridge: one column an hour, the temperature curve coloured by its own readings over a fading
 * fill, each hour's glyph and temperature above its point, night hours on a faint band, midnight as a rule with the
 * new day's name, and rain as bars under the baseline. Geometry is built when the data or size changes, never while
 * drawing.
 */
@SuppressLint("ViewConstructor")
class TouchWeatherRidgeView(context: Context) : View(context) {
    private var screen: TouchWeatherScreen? = null
    private var colors = TouchWeatherColors.DAY
    private var night = false
    private var wall = false
    private val glyphs = TouchWeatherGlyphs(context)
    private val curve = Path()
    private val area = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tempText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hourText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dayText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rainText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emptyText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bands = ArrayList<RectF>()
    private val rainBars = ArrayList<RectF>()
    private val rainLabels = ArrayList<Triple<Float, Float, String>>()
    private val midnights = ArrayList<Pair<Float, Pair<Float, String>>>()
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var pointColors = IntArray(0)
    private var tempLabels = emptyArray<String>()
    private var base = 0f
    private var hourRow = 0f
    private var top = 0f

    fun set(next: TouchWeatherScreen, nextColors: TouchWeatherColors, isNight: Boolean, isWall: Boolean) {
        screen = next
        colors = nextColors
        night = isNight
        wall = isWall
        rebuild()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuild()
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun pick(panel: Float, wallValue: Float) = if (wall) wallValue else panel

    private fun rebuild() {
        val data = screen ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        tempText.typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY_BOLD)
        tempText.textSize = sp(pick(22f, 30f))
        tempText.color = colors.ink
        hourText.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_MEDIUM)
        hourText.textSize = sp(pick(18f, 21f))
        hourText.color = colors.muted
        dayText.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_BOLD)
        dayText.textSize = sp(pick(18f, 21f))
        dayText.color = colors.ink
        rainText.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_BOLD)
        rainText.textSize = sp(pick(18f, 20f))
        rainText.color = colors.rain
        emptyText.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT)
        emptyText.textSize = sp(pick(22f, 26f))
        emptyText.color = colors.muted
        rulePaint.color = colors.rule

        bands.clear(); rainBars.clear(); rainLabels.clear(); midnights.clear()
        curve.reset(); area.reset()
        val hours = data.hours
        if (hours.size < 2) {
            xs = FloatArray(0)
            invalidate()
            return
        }
        val pad = dp(pick(32f, 48f))
        val icon = dp(pick(24f, 34f))
        val colW = (w - pad * 2) / TouchWeatherPresenter.HOURS
        hourRow = h - dp(pick(16f, 22f))
        val rainRow = hourRow - hourText.textSize - dp(pick(24f, 30f))
        base = (if (data.rainRuns.isNotEmpty()) rainRow - rainText.textSize - dp(8f) else rainRow + dp(4f)) - dp(pick(4f, 8f))
        top = dp(pick(24f, 34f)) + icon + tempText.textSize + dp(14f)
        // Short of room the curve flattens rather than its bottom rising above its top and drawing it upside down.
        val plotBottom = maxOf(base - dp(pick(52f, 70f)), top + dp(MIN_PLOT_DP))
        fun y(value: Int) = top + (1f - ((value - data.floor) / data.range).toFloat()) * (plotBottom - top)

        xs = FloatArray(hours.size) { pad + (it + 0.5f) * colW }
        ys = FloatArray(hours.size) { y(hours[it].temperature) }
        pointColors = IntArray(hours.size) { TouchWeatherColors.ramp(TouchWeatherColors.toFahrenheit(hours[it].temperature.toDouble(), data.fahrenheit), night) }
        tempLabels = Array(hours.size) { "${hours[it].temperature}°" }

        // Night hours on a faint band, so day and night read without a legend.
        var run = -1
        hours.forEachIndexed { k, hour ->
            if (hour.night && run < 0) run = k
            if (!hour.night && run >= 0) { bands.add(RectF(pad + run * colW, 0f, pad + k * colW, base)); run = -1 }
        }
        if (run >= 0) bands.add(RectF(pad + run * colW, 0f, pad + hours.size * colW, base))

        // Midnight: a rule and the new day's name, beside the rule while it fits and before it at the right edge.
        hours.forEachIndexed { k, hour ->
            val name = hour.dayStart ?: return@forEachIndexed
            val x = pad + k * colW
            val roomy = x + dp(pick(9f, 12f)) + dayText.measureText(name) < w - pad
            midnights.add(x to ((if (roomy) x + dp(pick(9f, 12f)) else x - dp(pick(9f, 12f)) - dayText.measureText(name)) to name))
        }

        val dx = DoubleArray(xs.size) { xs[it].toDouble() }
        val dy = DoubleArray(ys.size) { ys[it].toDouble() }
        val m = TouchWeatherCurve.tangents(dx, dy)
        curve.moveTo(xs[0], ys[0])
        for (i in 0 until xs.size - 1) {
            val c = TouchWeatherCurve.controls(dx, dy, m, i)
            curve.cubicTo(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(), xs[i + 1], ys[i + 1])
        }
        area.set(curve)
        area.lineTo(xs.last(), base)
        area.lineTo(xs.first(), base)
        area.close()

        val positions = FloatArray(xs.size) { ((xs[it] - xs.first()) / (xs.last() - xs.first())).coerceIn(0f, 1f) }
        val gradient = LinearGradient(xs.first(), 0f, xs.last(), 0f, pointColors, positions, Shader.TileMode.CLAMP)
        linePaint.shader = gradient
        linePaint.strokeWidth = dp(pick(4f, 5f))
        areaPaint.shader = LinearGradient(xs.first(), 0f, xs.last(), 0f, pointColors, positions, Shader.TileMode.CLAMP)
        areaPaint.alpha = if (night) 107 else 140
        // The fill fades toward the baseline: the field colour laid over the area, clear at the top.
        fadePaint.shader = LinearGradient(0f, top, 0f, base, colors.field and 0x00FFFFFF, (colors.field and 0x00FFFFFF) or (0x99 shl 24), Shader.TileMode.CLAMP)
        ringPaint.strokeWidth = dp(pick(2.5f, 3f))

        // Rain: one bar per run, thicker for more, labelled at its start unless the label would run into the last one.
        var labelEnd = Float.NEGATIVE_INFINITY
        data.rainRuns.forEach { rain ->
            val x0 = pad + rain.start * colW + dp(2f)
            val x1 = pad + rain.end * colW - dp(2f)
            val thickness = dp(pick(3f, 4f)) + rain.weight * dp(pick(8f, 10f))
            rainBars.add(RectF(x0, rainRow + dp(6f), x1, rainRow + dp(6f) + thickness))
            if (x0 >= labelEnd) {
                rainLabels.add(Triple(x0, rainRow, rain.label))
                labelEnd = x0 + rainText.measureText(rain.label) + dp(8f)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val data = screen ?: return
        if (xs.isEmpty()) {
            canvas.drawText("Home Assistant sent no hourly forecast.", width / 2f, height / 2f, emptyText)
            return
        }
        fillPaint.color = colors.nightBand
        bands.forEach { canvas.drawRect(it, fillPaint) }
        rulePaint.strokeWidth = dp(2f)
        midnights.forEach { (x, label) ->
            canvas.drawLine(x, dp(pick(6f, 10f)), x, hourRow + dp(4f), rulePaint)
            canvas.drawText(label.second, label.first, dp(pick(22f, 30f)), dayText)
        }
        canvas.drawPath(area, areaPaint)
        canvas.save()
        canvas.clipPath(area)
        canvas.drawRect(0f, top, width.toFloat(), base, fadePaint)
        canvas.restore()
        canvas.drawPath(curve, linePaint)
        rulePaint.strokeWidth = dp(1f)
        canvas.drawLine(dp(pick(32f, 48f)), base, width - dp(pick(32f, 48f)), base, rulePaint)

        val icon = dp(pick(24f, 34f))
        val lift = dp(pick(12f, 16f))
        data.hours.forEachIndexed { k, hour ->
            val x = xs[k]
            val y = ys[k]
            fillPaint.color = colors.field
            canvas.drawCircle(x, y, dp(pick(4f, 5f)), fillPaint)
            ringPaint.color = pointColors[k]
            canvas.drawCircle(x, y, dp(pick(4f, 5f)), ringPaint)
            canvas.drawText(tempLabels[k], x, y - lift, tempText)
            hour.glyph?.let { glyphs.draw(canvas, it, x - icon / 2f, y - lift - tempText.textSize - icon - dp(pick(0f, 2f)), icon, colors) }
            hour.axisLabel?.let { canvas.drawText(it, x, hourRow, hourText) }
        }
        fillPaint.color = colors.rain
        fillPaint.alpha = if (night) 179 else 204
        rainBars.forEach { canvas.drawRoundRect(it, it.height() / 2f, it.height() / 2f, fillPaint) }
        fillPaint.alpha = 255
        rainLabels.forEach { (x, y, label) -> canvas.drawText(label, x, y, rainText) }
    }

    private companion object {
        const val MIN_PLOT_DP = 24f
    }
}

/**
 * The week as a band: each day's high on a solid curve and low on a dotted one with the colour of the day between
 * them, the day's name, glyph and any rain above, today on a soft panel.
 */
@SuppressLint("ViewConstructor")
class TouchWeatherWeekView(context: Context) : View(context) {
    private var screen: TouchWeatherScreen? = null
    private var colors = TouchWeatherColors.DAY
    private var night = false
    private var wall = false
    private val glyphs = TouchWeatherGlyphs(context)
    private val highCurve = Path()
    private val lowCurve = Path()
    private val band = Path()
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val lowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dayText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val highText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val lowText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rainText = Paint(Paint.ANTI_ALIAS_FLAG)
    private var xs = FloatArray(0)
    private var highYs = FloatArray(0)
    private var lowYs = FloatArray(0)
    private var highColors = IntArray(0)
    private var lowColors = IntArray(0)
    private var highLabels = emptyArray<String>()
    private var lowLabels = emptyArray<String?>()
    private var colW = 0f
    private val today = RectF()

    fun set(next: TouchWeatherScreen, nextColors: TouchWeatherColors, isNight: Boolean, isWall: Boolean) {
        screen = next
        colors = nextColors
        night = isNight
        wall = isWall
        rebuild()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuild()
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun pick(panel: Float, wallValue: Float) = if (wall) wallValue else panel

    private fun rebuild() {
        val data = screen ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val days = data.days
        if (w <= 0f || h <= 0f || days.isEmpty()) return
        dayText.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_BOLD)
        dayText.textSize = sp(pick(20f, 24f))
        dayText.color = colors.ink
        highText.typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY_BOLD)
        highText.textSize = sp(pick(22f, 30f))
        highText.color = colors.ink
        lowText.typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY)
        lowText.textSize = sp(pick(18f, 24f))
        lowText.color = colors.muted
        rainText.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT_BOLD)
        rainText.textSize = sp(pick(18f, 20f))
        rainText.color = colors.rain
        rulePaint.color = colors.rule
        rulePaint.strokeWidth = dp(1f)

        val pad = dp(pick(32f, 48f))
        colW = (w - pad * 2) / days.size
        val nameY = dp(pick(30f, 40f))
        val iconTop = nameY + dp(pick(10f, 14f))
        val icon = dp(pick(30f, 40f))
        val bandTop = iconTop + icon + dp(pick(16f, 22f)) + highText.textSize
        val bandBottom = h - dp(pick(28f, 40f)) - lowText.textSize
        val lows = days.map { it.low ?: it.high }
        val tMin = lows.minOrNull() ?: 0
        val tMax = days.maxOf { it.high }
        val span = maxOf(tMax - tMin, 1).toFloat()
        fun y(value: Int) = bandTop + (1f - (value - tMin) / span) * (bandBottom - bandTop)
        xs = FloatArray(days.size) { pad + (it + 0.5f) * colW }
        highYs = FloatArray(days.size) { y(days[it].high) }
        lowYs = FloatArray(days.size) { y(lows[it]) }
        highColors = IntArray(days.size) { TouchWeatherColors.ramp(TouchWeatherColors.toFahrenheit(days[it].high.toDouble(), data.fahrenheit), night) }
        lowColors = IntArray(days.size) { TouchWeatherColors.ramp(TouchWeatherColors.toFahrenheit(lows[it].toDouble(), data.fahrenheit), night) }
        highLabels = Array(days.size) { "${days[it].high}°" }
        lowLabels = Array(days.size) { days[it].low?.let { low -> "$low°" } }
        val todayIndex = days.indexOfFirst { it.today }
        if (todayIndex >= 0) today.set(pad + todayIndex * colW + dp(4f), dp(pick(8f, 10f)), pad + (todayIndex + 1) * colW - dp(4f), h - dp(pick(8f, 10f))) else today.setEmpty()

        highCurve.reset(); lowCurve.reset(); band.reset()
        if (days.size >= 2) {
            path(highCurve, xs, highYs)
            path(lowCurve, xs, lowYs)
            // One closed contour: along the highs, down to the last low, back along the lows.
            path(band, xs, highYs)
            band.lineTo(xs.last(), lowYs.last())
            path(band, xs.reversedArray(), lowYs.reversedArray(), moveFirst = false)
            band.close()
            val middle = IntArray(days.size) {
                TouchWeatherColors.ramp(TouchWeatherColors.toFahrenheit((days[it].high + lows[it]) / 2.0, data.fahrenheit), night)
            }
            val positions = FloatArray(days.size) { ((xs[it] - xs.first()) / (xs.last() - xs.first())).coerceIn(0f, 1f) }
            bandPaint.shader = LinearGradient(xs.first(), 0f, xs.last(), 0f, middle, positions, Shader.TileMode.CLAMP)
            bandPaint.alpha = if (night) 97 else 128
            highPaint.shader = LinearGradient(xs.first(), 0f, xs.last(), 0f, middle, positions, Shader.TileMode.CLAMP)
            lowPaint.shader = LinearGradient(xs.first(), 0f, xs.last(), 0f, middle, positions, Shader.TileMode.CLAMP)
        }
        highPaint.strokeWidth = dp(pick(3f, 4f))
        lowPaint.strokeWidth = dp(pick(2f, 2.5f))
        lowPaint.pathEffect = DashPathEffect(floatArrayOf(dp(1f), dp(pick(6f, 7f))), 0f)
        lowPaint.alpha = 230
        ringPaint.strokeWidth = dp(pick(2.5f, 3f))
        invalidate()
    }

    /** Monotone curve through the points; [moveFirst] false continues the current contour instead of starting one. */
    private fun path(target: Path, px: FloatArray, py: FloatArray, moveFirst: Boolean = true) {
        val dx = DoubleArray(px.size) { px[it].toDouble() }
        val dy = DoubleArray(py.size) { py[it].toDouble() }
        if (px.size < 2) return
        // Reversed x runs right to left; the tangents only need x in order, so fit on the mirrored axis.
        val reversed = dx.first() > dx.last()
        val fitX = if (reversed) DoubleArray(dx.size) { -dx[it] } else dx
        val m = TouchWeatherCurve.tangents(fitX, dy)
        if (moveFirst) target.moveTo(px[0], py[0])
        for (i in 0 until px.size - 1) {
            val c = TouchWeatherCurve.controls(fitX, dy, m, i)
            val c1x = if (reversed) -c[0] else c[0]
            val c2x = if (reversed) -c[2] else c[2]
            target.cubicTo(c1x.toFloat(), c[1].toFloat(), c2x.toFloat(), c[3].toFloat(), px[i + 1], py[i + 1])
        }
    }

    override fun onDraw(canvas: Canvas) {
        val data = screen ?: return
        val days = data.days
        if (days.isEmpty() || xs.size != days.size) return
        canvas.drawColor(colors.ground)
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, rulePaint)
        if (!today.isEmpty) {
            fillPaint.color = colors.todayPanel
            canvas.drawRoundRect(today, dp(14f), dp(14f), fillPaint)
        }
        if (days.size >= 2) {
            canvas.drawPath(band, bandPaint)
            canvas.drawPath(highCurve, highPaint)
            canvas.drawPath(lowCurve, lowPaint)
        }
        val nameY = dp(pick(30f, 40f))
        val iconTop = nameY + dp(pick(10f, 14f))
        val icon = dp(pick(30f, 40f))
        days.forEachIndexed { k, day ->
            val x = xs[k]
            canvas.drawText(day.label, x, nameY, dayText)
            day.glyph?.let { glyphs.draw(canvas, it, x - icon / 2f, iconTop, icon, colors) }
            day.rain?.let { canvas.drawText(it, x + icon / 2f + dp(6f), iconTop + icon * 0.75f, rainText) }
            fillPaint.color = colors.ground
            canvas.drawCircle(x, highYs[k], dp(pick(4f, 5f)), fillPaint)
            ringPaint.color = highColors[k]
            canvas.drawCircle(x, highYs[k], dp(pick(4f, 5f)), ringPaint)
            canvas.drawText(highLabels[k], x, highYs[k] - dp(pick(10f, 14f)), highText)
            lowLabels[k]?.let { low ->
                fillPaint.color = lowColors[k]
                canvas.drawCircle(x, lowYs[k], dp(pick(3f, 4f)), fillPaint)
                canvas.drawText(low, x, lowYs[k] + lowText.textSize + dp(pick(6f, 10f)), lowText)
            }
        }
    }
}
