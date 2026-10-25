package com.wyattfleming.touchframe.ocean

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.wyattfleming.frameui.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Ebbward's Today surface for the wall: sky by the hour, the sun or moon on its arc, wind streaks, three swell
 * layers, the deep water, light shafts and a garibaldi. Following Ebbward's HeroScene, every moving part is drawn
 * once and only moved: the clock changes translation/rotation properties, never path data, so a frame is a
 * recomposite of cached drawings. Updates are capped at 20 per second, stop whenever the scene is hidden, and the
 * scene stays still when the system animation scale is off (reduced motion).
 */
@SuppressLint("ViewConstructor")
class TouchOceanScene(context: Context) : FrameLayout(context) {
    private val sky = View(context)
    private val body = SkyBodyView(context)
    private val streaks = List(STREAKS) { StreakView(context) }
    private val back = SwellView(context)
    private val middle = SwellView(context)
    private val front = SwellView(context)
    private val sea = SeaBodyView(context)
    private val shafts = List(2) { ShaftView(context) }
    private val fish = FrameLayout(context)
    private val fishTail = ImageView(context)
    private val fishBody = ImageView(context)

    private var spec: TouchOceanSceneSpec? = null
    private var night = false
    private var active = false
    private var geometry: Geometry? = null
    private var lastFrameMillis = -FRAME_MILLIS
    private val clock = TimeAnimator().apply { setTimeListener { _, totalMillis, _ -> onFrame(totalMillis) } }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(sky, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(0, 0))
        streaks.forEach { addView(it, LayoutParams(0, 0)) }
        listOf(back, middle).forEach { addView(it, LayoutParams(0, 0)) }
        addView(sea, LayoutParams(0, 0))
        addView(front, LayoutParams(0, 0))
        fish.addView(fishTail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fish.addView(fishBody, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fish, LayoutParams(0, 0))
        shafts.forEach { addView(it, LayoutParams(0, 0)) }
        // The swell bands only ever translate: keep each as one cached texture.
        listOf(back, middle, front).forEach { it.setLayerType(LAYER_TYPE_HARDWARE, null) }
    }

    fun render(next: TouchOceanSceneSpec, nextNight: Boolean) {
        if (next == spec && nextNight == night && geometry != null) return
        spec = next
        night = nextNight
        rebuild()
    }

    /** The activity's lifecycle and destination decide this; the clock also stops whenever the view is hidden. */
    fun setActive(value: Boolean) {
        active = value
        updateClock()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) post { rebuild() }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateClock()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateClock()
    }

    override fun onDetachedFromWindow() {
        clock.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateClock() {
        val run = active && isAttachedToWindow && isShown && geometry != null && animationsEnabled()
        if (run && !clock.isStarted) {
            lastFrameMillis = -FRAME_MILLIS
            clock.start()
        } else if (!run && clock.isStarted) {
            clock.cancel()
        }
    }

    private fun animationsEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    private fun onFrame(totalMillis: Long) {
        if (totalMillis - lastFrameMillis < FRAME_MILLIS) return
        lastFrameMillis = totalMillis
        geometry?.let { place(it, totalMillis / 1000.0) }
    }

    private fun dp(value: Double): Float = (value * resources.displayMetrics.density).toFloat()

    private fun rebuild() {
        val current = spec ?: return
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val wall = TouchOceanLayout.isWall(resources.configuration)
        val budget = if (wall) TouchOceanLayout.WALL else TouchOceanLayout.PANEL
        val k = if (wall) 2.0 else 1.5
        val amplitude = dp(current.amplitude * k)
        val waterline = dp(budget.waterlineTop + (budget.waterlineBottom - budget.waterlineTop) * current.waterlineFraction)
        val crestUp = amplitude * 2.2f + dp(14.0)
        val crestDown = amplitude * 1.2f + dp(24.0)
        val bandTop = waterline - crestUp
        val seaTop = waterline + crestDown - 1f
        val seaFrom = waterline - dp(20.0)

        val skyColors = TouchOceanSky.at(current.hour, current.sunrise, current.sunset, night)
        sky.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(skyColors.top, skyColors.horizon))
        val skyDark = TouchOceanSky.luminance(skyColors.top) < 0.28
        val tone = { color: Int -> if (night) TouchOceanSky.dim(color) else color }
        val seaStops = (if (night) TouchOceanWater.NIGHT else TouchOceanWater.DAY).map(tone).toIntArray()

        val layers = listOf(
            Layer(back, 0.45, -amplitude * 1.2f - dp(6.0), dp(120.0 * k * 1.6), 1.3, 0.9),
            Layer(middle, 0.7, -amplitude * 0.4f - dp(2.0), dp(175.0 * k * 1.6), 0.4, 1.0),
            Layer(front, 1.0, amplitude * 0.2f, dp(250.0 * k * 1.6), 0.0, 1.05),
        )
        layers.forEach { layer ->
            val bandWidth = (width + layer.wavelength + dp(24.0)).toInt()
            size(layer.view, bandWidth, (crestUp + crestDown).toInt())
            layer.view.translationY = bandTop
            layer.view.setWave(
                bandWidth = bandWidth.toFloat(),
                bandHeight = crestUp + crestDown,
                baseline = crestUp + layer.lift,
                amplitude = amplitude * layer.share.toFloat(),
                wavelength = layer.wavelength,
                offset = layer.offset,
                shiftX = layer.wavelength + dp(12.0),
                fill = when (layer.view) {
                    back -> FillSpec.Solid(withAlpha(tone(if (night) 0xFF245E58.toInt() else 0xFF9CCFCD.toInt()), 0.75))
                    middle -> FillSpec.Solid(withAlpha(tone(if (night) 0xFF1F6A62.toInt() else 0xFF6DB2B3.toInt()), 0.9))
                    else -> FillSpec.Sea(seaStops, seaFrom - bandTop, height - bandTop)
                },
                crest = if (layer.view === front) (if (night) withAlpha(0xFFB39668.toInt(), 0.35) else withAlpha(0xFFF2FBFA.toInt(), 0.7)) else null,
                crestWidth = dp(if (wall) 2.0 else 1.5),
            )
        }
        size(sea, width.toInt(), (height - seaTop).toInt().coerceAtLeast(1))
        sea.translationY = seaTop
        sea.setGradient(seaStops, seaFrom - seaTop, height - seaTop)

        // Sun or moon on its arc between the header and the waterline.
        val radius = dp(if (wall) 26.0 else 19.0)
        val arcBody = TouchOceanSky.body(current.hour, current.sunrise, current.sunset)
        body.visibility = if (arcBody == null) View.GONE else View.VISIBLE
        if (arcBody != null) {
            size(body, (radius * 2).toInt() + 2, (radius * 2).toInt() + 2)
            val head = dp(budget.header)
            val top = dp(budget.waterlineTop) - dp(40.0)
            val x = width * (0.5f + 0.38f * ((arcBody.progress - 0.5) * 2).toFloat())
            val y = top - sin(PI * arcBody.progress.coerceIn(0.0, 1.0)).toFloat() * max(0f, top - head - radius)
            body.translationX = x - radius
            body.translationY = y - radius
            body.set(arcBody.moon, radius, if (arcBody.moon) (if (night) 0xFF8E7A58.toInt() else 0xFFEAF2F1.toInt()) else tone(0xFFFFF6E0.toInt()))
        }

        // Wind streaks fill the sky below the header text; their length follows the wind.
        val streakLength = dp((18 + TouchOceanUnits.mph(current.windKmh) * 2.2) * k * 0.8)
        val streakColor = when {
            night -> withAlpha(0xFFB39668.toInt(), 0.25)
            skyDark -> withAlpha(0xFFF2F9F8.toInt(), 0.45)
            else -> withAlpha(0xFF0B2A36.toInt(), 0.25)
        }
        val head = dp(budget.header)
        val streakBand = dp(budget.waterlineTop) - dp(36.0) - head
        streaks.forEachIndexed { index, streak ->
            size(streak, (streakLength + dp(8.0)).toInt(), dp(10.0).toInt())
            streak.translationY = head + dp(12.0) + index * streakBand / STREAKS
            streak.set(streakLength, streakColor, dp(if (wall) 2.0 else 1.5))
        }

        // Light shafts by day only: a bedroom frame at night stays dark.
        shafts.forEachIndexed { index, shaft ->
            shaft.visibility = if (night) View.GONE else View.VISIBLE
            val x0 = width * if (index == 0) -0.12f else 0.33f
            val x1 = width * if (index == 0) 0.3f else 0.58f
            size(shaft, (x1 - x0).toInt(), (height - waterline).toInt().coerceAtLeast(1))
            shaft.translationY = waterline
            shaft.set(
                if (index == 0) floatArrayOf(width * 0.18f - x0, width * 0.3f - x0, width * 0.05f - x0, width * -0.12f - x0)
                else floatArrayOf(width * 0.52f - x0, width * 0.58f - x0, width * 0.44f - x0, width * 0.33f - x0),
            )
        }

        val fishLength = dp(if (wall) 72.0 else 52.0)
        size(fish, fishLength.toInt(), (fishLength * 0.54f).toInt())
        fishTail.setImageResource(if (night) R.drawable.ocean_garibaldi_tail_night else R.drawable.ocean_garibaldi_tail_day)
        fishBody.setImageResource(if (night) R.drawable.ocean_garibaldi_body_night else R.drawable.ocean_garibaldi_body_day)
        fishTail.pivotX = fishLength * 0.23f
        fishTail.pivotY = fishLength * 0.27f

        val geometryNext = Geometry(
            width = width,
            layers = layers,
            period = current.periodS,
            streakSpan = (width + dp(80.0 * k)).toDouble(),
            streakSpacing = dp(397.0).toDouble(),
            streakSpeed = dp((8 + current.windKmh * 2.8) * k).toDouble(),
            streakDirection = if (current.windTowardEast) 1 else -1,
            sway = dp(8.0 * k),
            shaftLefts = floatArrayOf(width * -0.12f, width * 0.33f),
            fishLength = fishLength,
            fishRow = max(dp(budget.fish), waterline + amplitude + dp(24.0)) - fishLength * 0.27f,
            fishSpeed = dp(8.7 * k),
            fishBob = dp(3.0 * k),
        )
        geometry = geometryNext
        place(geometryNext, 0.0)
        updateClock()
    }

    /** Every per-frame change is a view property; nothing is redrawn. */
    private fun place(g: Geometry, seconds: Double) {
        g.layers.forEach { layer ->
            val phase = fraction(seconds * layer.speed / g.period)
            layer.view.translationX = -(layer.wavelength + dp(12.0)) + phase.toFloat() * layer.wavelength
        }
        streaks.forEachIndexed { index, streak ->
            streak.translationX = TouchOceanWaves.streakX(index, seconds, g.streakSpan, g.streakSpacing, g.streakSpeed, g.streakDirection).toFloat() - dp(40.0)
        }
        val sway = sin(2 * PI * fraction(seconds / g.period)).toFloat() * g.sway
        shafts.forEachIndexed { index, shaft -> shaft.translationX = g.shaftLefts[index] + if (index == 0) sway else -sway }

        // The garibaldi crosses, turns and comes back. Time zero (and every still scene) is 60% across, heading left,
        // so starting the clock never makes it jump.
        val span = g.width + g.fishLength * 2
        val travelled = seconds * g.fishSpeed + (span - g.fishLength - g.width * 0.6f)
        val lap = (travelled / span).toLong()
        val offset = (travelled % span).toFloat()
        val towardRight = lap % 2L == 1L
        val x = if (towardRight) offset - g.fishLength else g.width + g.fishLength - offset
        fish.translationX = x - g.fishLength / 2
        fish.translationY = g.fishRow + sin(2 * PI * seconds / FISH_BOB_SECONDS).toFloat() * g.fishBob
        fish.scaleX = if (towardRight) 1f else -1f
        fishTail.rotation = sin(2 * PI * seconds * 1000 / TAIL_PERIOD_MILLIS).toFloat() * TAIL_DEGREES
    }

    private fun size(view: View, width: Int, height: Int) {
        val params = view.layoutParams as LayoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            view.layoutParams = params
        }
    }

    private class Layer(val view: SwellView, val share: Double, val lift: Float, val wavelength: Float, val offset: Double, val speed: Double)

    private class Geometry(
        val width: Float,
        val layers: List<Layer>,
        val period: Double,
        val streakSpan: Double,
        val streakSpacing: Double,
        val streakSpeed: Double,
        val streakDirection: Int,
        val sway: Float,
        val shaftLefts: FloatArray,
        val fishLength: Float,
        val fishRow: Float,
        val fishSpeed: Float,
        val fishBob: Float,
    )

    companion object {
        private const val STREAKS = 7
        /** At most 20 scene updates per second: the swell moves about a pixel per update. */
        // Equal to TouchMotion.SCENE_FRAME_MILLIS (TouchMotionTest); kept literal for TouchOceanSceneSourceTest.
        const val FRAME_MILLIS = 50L
        private const val FISH_BOB_SECONDS = 15.0
        private const val TAIL_PERIOD_MILLIS = 1_150.0
        private const val TAIL_DEGREES = 9f

        fun fraction(value: Double): Double = value - kotlin.math.floor(value)
        fun withAlpha(color: Int, alpha: Double): Int = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
    }
}

sealed class FillSpec {
    data class Solid(val color: Int) : FillSpec()
    class Sea(val stops: IntArray, val fromY: Float, val toY: Float) : FillSpec()
}

/** One swell band: a periodic wave drawn once into this view; the scene only slides it. */
@SuppressLint("ViewConstructor")
class SwellView(context: Context) : View(context) {
    private val fillPath = Path()
    private val crestPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val crestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var drawCrest = false

    fun setWave(
        bandWidth: Float,
        bandHeight: Float,
        baseline: Float,
        amplitude: Float,
        wavelength: Float,
        offset: Double,
        shiftX: Float,
        fill: FillSpec,
        crest: Int?,
        crestWidth: Float,
    ) {
        val step = 12f * resources.displayMetrics.density
        fillPath.reset()
        crestPath.reset()
        var x = 0f
        while (x <= bandWidth + step) {
            val y = baseline + TouchOceanWaves.y((x - shiftX).toDouble(), amplitude.toDouble(), wavelength.toDouble(), offset).toFloat()
            if (x == 0f) { fillPath.moveTo(x, y); crestPath.moveTo(x, y) } else { fillPath.lineTo(x, y); crestPath.lineTo(x, y) }
            x += step
        }
        fillPath.lineTo(x - step, bandHeight)
        fillPath.lineTo(0f, bandHeight)
        fillPath.close()
        when (fill) {
            is FillSpec.Solid -> { fillPaint.shader = null; fillPaint.color = fill.color }
            is FillSpec.Sea -> fillPaint.shader = LinearGradient(0f, fill.fromY, 0f, fill.toY, fill.stops, floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        drawCrest = crest != null
        if (crest != null) { crestPaint.color = crest; crestPaint.strokeWidth = crestWidth }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(fillPath, fillPaint)
        if (drawCrest) canvas.drawPath(crestPath, crestPaint)
    }
}

/** The deep water under the front band, continuing the band's own gradient rows. */
@SuppressLint("ViewConstructor")
class SeaBodyView(context: Context) : View(context) {
    private val paint = Paint()

    fun setGradient(stops: IntArray, fromY: Float, toY: Float) {
        paint.shader = LinearGradient(0f, fromY, 0f, toY, stops, floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/** The sun, or a crescent moon cut with a second circle. */
@SuppressLint("ViewConstructor")
class SkyBodyView(context: Context) : View(context) {
    private val path = Path()
    private val cut = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(moon: Boolean, radius: Float, color: Int) {
        paint.color = color
        path.reset()
        val c = radius + 1f
        if (moon) {
            path.addCircle(c, c, radius * 0.85f, Path.Direction.CW)
            cut.reset()
            cut.addCircle(c + radius * 0.4f, c - radius * 0.25f, radius * 0.78f, Path.Direction.CW)
            path.op(cut, Path.Op.DIFFERENCE)
        } else {
            path.addCircle(c, c, radius, Path.Direction.CW)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }
}

/** One wind streak: a shallow arc in a box of its own. */
@SuppressLint("ViewConstructor")
class StreakView(context: Context) : View(context) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    fun set(length: Float, color: Int, strokeWidth: Float) {
        val inset = 4f * resources.displayMetrics.density
        path.reset()
        path.moveTo(inset, inset + 2f)
        path.quadTo(inset + length / 2, inset - 1f, inset + length, inset + 2f)
        paint.color = color
        paint.strokeWidth = strokeWidth
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }
}

/** A light shaft below the surface: a quad fading from 8% white (0x14) to nothing. */
@SuppressLint("ViewConstructor")
class ShaftView(context: Context) : View(context) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Top-left x, top-right x, bottom-right x, bottom-left x, in this view's coordinates. */
    fun set(xs: FloatArray) {
        val bottom = height.toFloat().takeIf { it > 0f } ?: (layoutParams?.height?.toFloat() ?: 0f)
        path.reset()
        path.moveTo(xs[0], 0f)
        path.lineTo(xs[1], 0f)
        path.lineTo(xs[2], bottom)
        path.lineTo(xs[3], bottom)
        path.close()
        val right = layoutParams?.width?.toFloat() ?: width.toFloat()
        paint.shader = LinearGradient(0f, 0f, right, bottom, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }
}
