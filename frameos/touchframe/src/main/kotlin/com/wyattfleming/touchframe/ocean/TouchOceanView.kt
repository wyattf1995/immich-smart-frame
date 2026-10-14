package com.wyattfleming.touchframe.ocean

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.wyattfleming.touchframe.ui.TouchFonts
import com.wyattfleming.touchframe.ui.TouchKit
import com.wyattfleming.touchframe.ui.TouchPalette
import com.wyattfleming.touchframe.ui.TouchScale
import java.time.ZoneId
import kotlin.math.abs

/**
 * Fixed vertical budget (dp from the content top) so a high or low tide never moves the waterline into the text.
 * Below [Budget.heroTop] the conditions and the tide stack in one column, and the chart takes the height that is left.
 */
internal object TouchOceanLayout {
    data class Budget(val header: Double, val waterlineTop: Double, val waterlineBottom: Double, val fish: Double, val heroTop: Double)

    val WALL = Budget(header = 150.0, waterlineTop = 270.0, waterlineBottom = 350.0, fish = 398.0, heroTop = 424.0)
    val PANEL = Budget(header = 118.0, waterlineTop = 202.0, waterlineBottom = 252.0, fish = 276.0, heroTop = 292.0)

    fun isWall(configuration: Configuration): Boolean = TouchScale.forScreen(configuration) == TouchScale.WALL
}

/**
 * Ocean conditions at one beach over Ebbward's animated surface: the place and air in the sky, the water
 * temperature and swell under the waterline, and the next 24 hours of tide along the bottom. Rebuilds only when
 * what it shows changes; the scene animates by itself while [setActive] is true.
 */
@SuppressLint("ViewConstructor")
internal class TouchOceanView(context: Context, private val onRetry: () -> Unit) : FrameLayout(context) {
    private val scene = TouchOceanScene(context)
    private val overlay = FrameLayout(context)
    private var state = TouchOceanState()
    private var night = false
    private var renderedKey: Any? = null
    private var tickSlot = -1L

    init {
        addView(scene, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun render(next: TouchOceanState) {
        state = next
        rebuild()
    }

    fun setNight(value: Boolean) {
        if (night == value) return
        night = value
        rebuild()
    }

    /**
     * While the scene moves, the text and chart above it are one cached texture: each scene update redraws the whole
     * window, and without the layer every one re-issues the overlay's ~100 text and chart draws. Released when hidden.
     */
    fun setActive(value: Boolean) {
        scene.setActive(value)
        overlay.setLayerType(if (value) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, null)
    }

    /** Called every second by the activity clock; the screen is re-derived once per five-minute slot at most. */
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
        val minute = System.currentTimeMillis() / SLOT_MILLIS * SLOT_MILLIS
        val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
        val screen = state.conditions?.let { TouchOceanPresenter.present(it, minute, ZoneId.systemDefault(), twentyFour) }
        val scale = TouchScale.forScreen(resources.configuration)
        val key = listOf(screen, night, scale, state.connection, state.loading, state.conditions == null)
        if (key == renderedKey) return
        renderedKey = key

        val spec = screen?.scene ?: idleScene(minute)
        scene.render(spec, night)
        val kit = TouchKit(context, TouchPalette.forNight(night), scale)
        val budget = if (scale == TouchScale.WALL) TouchOceanLayout.WALL else TouchOceanLayout.PANEL
        val sky = TouchOceanSky.at(spec.hour, spec.sunrise, spec.sunset, night)
        val skyInk = when {
            night -> NIGHT_INK
            TouchOceanSky.luminance(sky.top) < 0.28 -> 0xFFF2F9F8.toInt()
            else -> 0xFF0B2A36.toInt()
        }
        val waterInk = if (night) NIGHT_INK else 0xFFFFFFFF.toInt()
        val waterMuted = if (night) NIGHT_MUTED else 0xFFE4F3F2.toInt()
        val pad = kit.dp(scale.pad)

        overlay.removeAllViews()
        overlay.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(kit.text(screen?.title ?: "Main Beach", scale.title, TouchFonts.Face.DISPLAY_BOLD, skyInk, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP))
            (screen?.place ?: "Laguna Beach, CA").let { addView(kit.text(it, scale.label, color = skyInk, maxLines = 1).apply { alpha = 0.85f }, top(kit, 4)) }
            screen?.skyLine?.let { addView(kit.text(it, scale.label, color = skyInk, maxLines = 1), top(kit, 10)) }
        }, FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = pad; topMargin = (pad * 0.7f).toInt() })
        overlay.addView(kit.text(statusLine(screen), scale.caption, color = skyInk, maxLines = 2).apply {
            alpha = 0.85f
            gravity = Gravity.END
        }, FrameLayout.LayoutParams(kit.dp(if (scale == TouchScale.WALL) 620 else 420), WRAP, Gravity.END or Gravity.TOP).apply {
            rightMargin = pad
            topMargin = (pad * 0.7f).toInt() + kit.dp(6)
        })

        if (screen == null) {
            emptyState(kit, budget, waterInk, waterMuted)
            return
        }
        overlay.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(waterRow(kit, screen, waterInk, waterMuted), LinearLayout.LayoutParams(MATCH, WRAP))
            addView(kit.text(screen.summary, scale.body, color = waterMuted, maxLines = 2).apply { setLineSpacing(0f, 1.3f) },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(scale.gap / 2) })
            addView(tideSection(kit, screen, waterInk, waterMuted), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(scale.gap / 2) })
        }, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            topMargin = kit.dp(budget.heroTop.toInt())
            leftMargin = pad
            rightMargin = pad
            bottomMargin = (pad * 0.45f).toInt()
        })
    }

    private fun statusLine(screen: TouchOceanScreen?): String = when {
        screen == null && state.loading -> "Getting conditions from Ebbward…"
        screen == null -> ""
        state.connection == TouchOceanConnection.OFFLINE -> "Can’t reach Ebbward. Showing conditions from ${screen.updated}."
        else -> "Updated ${screen.updated}"
    }

    /** The water temperature beside the swell, wind and sun, top-aligned to the numeral's digits rather than its line box. */
    private fun waterRow(kit: TouchKit, screen: TouchOceanScreen, ink: Int, muted: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val scale = kit.scale
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            screen.water?.let { water ->
                contentDescription = "Water temperature $water degrees Fahrenheit"
                val number = kit.text(water, scale.clock, TouchFonts.Face.DISPLAY, ink).apply { fontFeatureSettings = "tnum" }
                val degrees = kit.text("°F", scale.clock * 0.3f, TouchFonts.Face.DISPLAY, ink)
                val (numberTop, numberBottom) = inkInsets(number)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(number, LinearLayout.LayoutParams(WRAP, WRAP))
                    addView(degrees, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = numberTop - inkInsets(degrees).first })
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    gravity = Gravity.TOP
                    topMargin = -numberTop
                    bottomMargin = -numberBottom
                })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(kit.text("Water temperature", scale.headline, TouchFonts.Face.TEXT_MEDIUM, ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP))
                addView(kit.text("Blended NOAA model", scale.label, color = muted, maxLines = 1), top(kit, 4))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(scale.pad / 2) })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            screen.stats.forEach { stat ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    contentDescription = listOfNotNull("${stat.label} ${stat.value} ${stat.unit}", stat.detail).joinToString(", ")
                    addView(kit.text(stat.label, scale.label, color = muted, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.BOTTOM
                        addView(kit.text(stat.value, scale.display * 0.8f, TouchFonts.Face.DISPLAY_BOLD, ink, maxLines = 1).apply { fontFeatureSettings = "tnum" },
                            LinearLayout.LayoutParams(WRAP, WRAP))
                        if (stat.unit.isNotEmpty()) addView(kit.text(stat.unit, scale.display * 0.34f, TouchFonts.Face.DISPLAY, ink, maxLines = 1),
                            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(8); bottomMargin = kit.dp(6) })
                    })
                    stat.detail?.let { addView(kit.text(it, scale.label, color = ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP)) }
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
        }, LinearLayout.LayoutParams(0, WRAP, 1.2f).apply { marginStart = kit.dp(scale.pad) })
    }

    private fun tideSection(kit: TouchKit, screen: TouchOceanScreen, ink: Int, muted: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val scale = kit.scale
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(kit.text("Tide", scale.headline, TouchFonts.Face.TEXT_BOLD, ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP))
            screen.tideNow?.let {
                addView(kit.text(it, scale.title, TouchFonts.Face.DISPLAY_BOLD, ink, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(14) })
            }
            screen.tideTrend?.let {
                addView(kit.text(it, scale.body, color = muted, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(12); bottomMargin = kit.dp(2) })
            }
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            screen.tideNext.forEach {
                addView(kit.text(it, scale.label, color = muted, maxLines = 1), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = kit.dp(scale.pad) })
            }
        })
        val isNight = night
        val isWall = kit.scale == TouchScale.WALL
        val chart = screen.chart
        if (chart == null) {
            addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            addView(TouchTideChartView(context).apply {
                set(chart, ink, muted, isNight, isWall)
                contentDescription = listOfNotNull(
                    screen.tideNow?.let { now -> "Tide $now ${screen.tideTrend ?: ""}".trim() },
                    screen.tideNext.joinToString(". ").ifEmpty { null },
                ).joinToString(". ")
            }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = kit.dp(8) })
        }
        addView(kit.text(screen.source, scale.caption, color = muted, maxLines = 1), top(kit, 6))
    }

    private fun emptyState(kit: TouchKit, budget: TouchOceanLayout.Budget, ink: Int, muted: Int) {
        val scale = kit.scale
        overlay.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            if (state.loading) {
                addView(kit.text("Getting today’s conditions…", scale.headline, TouchFonts.Face.TEXT_MEDIUM, ink), LinearLayout.LayoutParams(MATCH, WRAP))
            } else {
                addView(kit.text("Ocean conditions aren’t available right now", scale.headline, TouchFonts.Face.TEXT_MEDIUM, ink), LinearLayout.LayoutParams(MATCH, WRAP))
                addView(kit.text("Ebbward didn’t answer. Check the frame’s Wi‑Fi, then try again.", scale.body, color = muted), top(kit, 10))
                addView(kit.button("Try again", TouchKit.Emphasis.SECONDARY) { onRetry() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(24) })
            }
        }, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = kit.dp(budget.heroTop.toInt())
            leftMargin = kit.dp(scale.pad)
            rightMargin = kit.dp(scale.pad)
        })
    }

    /** Before the first answer: a calm sea at mid tide under the current hour's sky. */
    private fun idleScene(nowMillis: Long): TouchOceanSceneSpec {
        val local = java.time.Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        return TouchOceanSceneSpec(local.hour + local.minute / 60.0, 6.5, 18.5, 0.5, 3.0, 10.0, 0.0, false)
    }

    private fun top(kit: TouchKit, dp: Int) = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = kit.dp(dp) }

    /** Blank space above and below [view]'s glyphs: a 150sp numeral's line box is more than a third empty. */
    private fun inkInsets(view: TextView): Pair<Int, Int> {
        val metrics = view.paint.fontMetricsInt
        val ink = Rect().also { view.paint.getTextBounds(view.text.toString(), 0, view.text.length, it) }
        return (ink.top - metrics.ascent).coerceAtLeast(0) to (metrics.descent - ink.bottom).coerceAtLeast(0)
    }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        /** The chart's now marker moves about a pixel in five minutes; nothing else changes faster. */
        const val SLOT_MILLIS = 5 * 60_000L
        /** Touch's night ink and muted ink (TouchPalette.NIGHT), so Ocean reads like every other screen at night. */
        val NIGHT_INK = 0xFFB39668.toInt()
        val NIGHT_MUTED = 0xFF8E7A58.toInt()
    }
}

/**
 * The next 24 hours of tide: fitted to the window's own range so the day's swing fills the chart, the past dotted,
 * the future solid over a soft fill, and the current level marked. Paths are built when the data or size changes,
 * never while drawing.
 */
@SuppressLint("ViewConstructor")
internal class TouchTideChartView(context: Context) : View(context) {
    private var chart: TouchOceanChart? = null
    private var ink = 0
    private var muted = 0
    private var night = false
    private var wall = false
    private val past = Path()
    private val future = Path()
    private val pastFill = Path()
    private val futureFill = Path()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pastFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val boldText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = ArrayList<Label>()
    private val dots = ArrayList<Dot>()
    private var nowX = 0f
    private var nowY = 0f
    private var base = 0f
    private var axisY = 0f
    private var top = 0f

    private class Dot(val x: Float, val y: Float, val color: Int)

    private class Label(val x: Float, val y: Float, val bold: String, val regular: String, val color: Int, val align: Paint.Align)

    fun set(next: TouchOceanChart, inkColor: Int, mutedColor: Int, isNight: Boolean, isWall: Boolean) {
        chart = next
        ink = inkColor
        muted = mutedColor
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

    private fun rebuild() {
        val data = chart ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || data.points.size < 2) return
        top = dp(if (wall) 46f else 36f)
        base = h - dp(if (wall) 36f else 30f)
        axisY = h - dp(if (wall) 4f else 3f)
        val plotBottom = base - dp(if (wall) 34f else 28f)
        val span = (data.toMillis - data.fromMillis).toFloat()
        fun x(millis: Long) = (millis - data.fromMillis) / span * w
        fun y(meters: Double) = top + (1f - ((meters - data.minM) / (data.maxM - data.minM)).toFloat()) * (plotBottom - top)

        past.reset(); future.reset(); pastFill.reset(); futureFill.reset()
        nowX = x(data.nowMillis)
        nowY = y(data.nowHeightM)
        val before = data.points.filter { it.epochMillis <= data.nowMillis }
        val after = data.points.filter { it.epochMillis >= data.nowMillis }
        before.forEachIndexed { index, point -> if (index == 0) past.moveTo(x(point.epochMillis), y(point.value)) else past.lineTo(x(point.epochMillis), y(point.value)) }
        if (before.isNotEmpty()) past.lineTo(nowX, nowY)
        future.moveTo(nowX, nowY)
        after.forEach { future.lineTo(x(it.epochMillis), y(it.value)) }
        pastFill.set(past)
        if (before.isNotEmpty()) { pastFill.lineTo(nowX, base); pastFill.lineTo(x(before.first().epochMillis), base); pastFill.close() }
        futureFill.set(future)
        val lastX = after.lastOrNull()?.let { x(it.epochMillis) } ?: nowX
        futureFill.lineTo(lastX, base); futureFill.lineTo(nowX, base); futureFill.close()

        val fillAlpha = if (night) 0.16 else 0.28
        fillPaint.shader = LinearGradient(0f, top, 0f, base, withAlpha(ink, fillAlpha), withAlpha(ink, 0.0), Shader.TileMode.CLAMP)
        pastFillPaint.shader = LinearGradient(0f, top, 0f, base, withAlpha(ink, fillAlpha * 0.35), withAlpha(ink, 0.0), Shader.TileMode.CLAMP)
        linePaint.color = ink
        linePaint.strokeWidth = dp(if (wall) 5f else 4f)
        pastPaint.color = withAlpha(muted, 0.75)
        pastPaint.strokeWidth = dp(if (wall) 3f else 2.5f)
        pastPaint.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(9f)), 0f)
        axisPaint.color = withAlpha(muted, 0.4)
        axisPaint.strokeWidth = dp(1f)
        dotPaint.color = ink
        ringPaint.color = if (night) 0xFF050F0E.toInt() else 0xFF1D6476.toInt()
        ringPaint.strokeWidth = dp(3f)
        boldText.typeface = TouchFonts.get(context, TouchFonts.Face.DISPLAY_BOLD)
        boldText.textSize = sp(if (wall) 22f else 18f)
        text.typeface = TouchFonts.get(context, TouchFonts.Face.TEXT)
        text.textSize = sp(if (wall) 20f else 17f)

        labels.clear()
        dots.clear()
        data.ticks.forEach { (millis, label) -> labels.add(Label(x(millis), axisY, "", label, muted, Paint.Align.CENTER)) }
        labels.add(Label(nowX + dp(10f), top - dp(10f), "Now", "", ink, Paint.Align.LEFT))
        val keepClear = dp(if (wall) 110f else 80f)
        val edge = dp(70f)
        data.events.forEach { event ->
            val ex = x(event.epochMillis)
            val ey = y(event.heightM)
            val color = if (event.epochMillis < data.nowMillis) muted else ink
            dots.add(Dot(ex, ey, color))
            if (abs(ex - nowX) < keepClear) return@forEach
            val align = when { ex < edge -> Paint.Align.LEFT; ex > w - edge -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val ly = if (event.high) ey - dp(14f) else minOf(ey + dp(28f), base - dp(6f))
            labels.add(Label(ex, ly, event.height, " ${event.time}", color, align))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (chart == null) return
        canvas.drawLine(0f, base, width.toFloat(), base, axisPaint)
        canvas.drawPath(pastFill, pastFillPaint)
        canvas.drawPath(futureFill, fillPaint)
        canvas.drawPath(past, pastPaint)
        canvas.drawPath(future, linePaint)
        canvas.drawLine(nowX, top - dp(26f), nowX, base, axisPaint.also { it.color = ink; it.strokeWidth = dp(2f) })
        axisPaint.color = withAlpha(muted, 0.4)
        axisPaint.strokeWidth = dp(1f)
        dots.forEach { dot ->
            dotPaint.color = dot.color
            canvas.drawCircle(dot.x, dot.y, dp(if (wall) 5.5f else 4.5f), dotPaint)
        }
        dotPaint.color = ink
        canvas.drawCircle(nowX, nowY, dp(if (wall) 9f else 7f), dotPaint)
        canvas.drawCircle(nowX, nowY, dp(if (wall) 9f else 7f), ringPaint)
        labels.forEach { label ->
            val boldWidth = if (label.bold.isEmpty()) 0f else boldText.measureText(label.bold)
            val regularWidth = if (label.regular.isEmpty()) 0f else text.measureText(label.regular)
            val total = boldWidth + regularWidth
            val start = when (label.align) { Paint.Align.LEFT -> label.x; Paint.Align.RIGHT -> label.x - total; else -> label.x - total / 2 }
            if (label.bold.isNotEmpty()) { boldText.color = label.color; canvas.drawText(label.bold, start, label.y, boldText) }
            if (label.regular.isNotEmpty()) { text.color = if (label.bold.isEmpty()) label.color else muted; canvas.drawText(label.regular, start + boldWidth, label.y, text) }
        }
    }

    private fun withAlpha(color: Int, alpha: Double): Int = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
}
