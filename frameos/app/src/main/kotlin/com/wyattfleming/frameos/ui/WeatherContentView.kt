package com.wyattfleming.frameos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import com.wyattfleming.frameos.weather.WeatherCondition
import com.wyattfleming.frameos.weather.WeatherForecastItem
import com.wyattfleming.frameos.weather.WeatherHourlyPager
import com.wyattfleming.frameos.weather.WeatherMetric
import com.wyattfleming.frameos.weather.WeatherPresentation
import com.wyattfleming.frameos.weather.WeatherRenderCadence
import com.wyattfleming.frameos.weather.sceneProfile
import kotlin.math.cos
import kotlin.math.sin

class WeatherContentView(context: Context) : View(context) {
    var presentation: WeatherPresentation = previewPresentation()
        set(value) {
            if (field.sceneCondition != value.sceneCondition) sceneEpochMillis = SystemClock.uptimeMillis()
            field = value
            pageEpochMillis = SystemClock.uptimeMillis()
            pageAnchorIndex = 0
            contentDescription = value.emptyMessage ?: "${value.condition}, ${value.temperature}. ${value.status}"
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val terrainPath = Path()
    private val hourlyPager = WeatherHourlyPager(WeatherRenderCadence.HOURLY_PAGE_SIZE)
    private var pageEpochMillis = SystemClock.uptimeMillis()
    private var pageAnchorIndex = 0
    private var sceneEpochMillis = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Clear night, 79°F. Preview weather"
    }

    fun moveHourlyPage(forward: Boolean): String? {
        val pageCount = hourlyPager.pageCount(presentation.hourly)
        if (pageCount <= 1) return null
        val currentPage = currentHourlyPageIndex(pageCount, SystemClock.uptimeMillis())
        pageAnchorIndex = Math.floorMod(currentPage + if (forward) 1 else -1, pageCount)
        pageEpochMillis = SystemClock.uptimeMillis()
        invalidate()

        val page = hourlyPager.page(presentation.hourly, pageAnchorIndex)
        val first = pageAnchorIndex * WeatherRenderCadence.HOURLY_PAGE_SIZE + 1
        val last = minOf(first + page.size - 1, presentation.hourly.size)
        return "Hours $first–$last"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        drawBackground(canvas, (now - sceneEpochMillis) / 1_000f)
        drawText(canvas, "Weather", width * 0.05f, height * 0.10f, 40f, Color.WHITE, true)

        val emptyMessage = presentation.emptyMessage
        if (emptyMessage != null) {
            drawEmptyState(canvas, emptyMessage)
        } else {
            drawText(canvas, presentation.status, width * 0.05f, height * 0.145f, 19f, MUTED)
            drawCurrent(canvas)
            drawDaily(canvas)
            drawHourly(canvas)
        }

        if (isShown) {
            WeatherRenderCadence.nextDelayMillis(
                sceneAnimated = presentation.sceneCondition.sceneProfile().animated,
                hourlyItemCount = presentation.hourly.size,
                pageElapsedMillis = now - pageEpochMillis,
            )?.let(::postInvalidateDelayed)
        }
    }

    private fun drawBackground(canvas: Canvas, elapsedSeconds: Float) {
        val condition = presentation.sceneCondition
        val profile = condition.sceneProfile()
        val colors = when (condition) {
            WeatherCondition.SUNNY -> intArrayOf(0xFF226AA0.toInt(), 0xFF69A9C6.toInt(), 0xFFD9A466.toInt())
            WeatherCondition.RAINY, WeatherCondition.POURING, WeatherCondition.LIGHTNING_RAINY ->
                intArrayOf(0xFF182431.toInt(), 0xFF33495B.toInt(), 0xFF48545D.toInt())
            WeatherCondition.CLOUDY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.FOG,
            WeatherCondition.WINDY, WeatherCondition.WINDY_VARIANT ->
                intArrayOf(0xFF26374B.toInt(), 0xFF61758A.toInt(), 0xFF8B8584.toInt())
            WeatherCondition.SNOWY, WeatherCondition.SNOWY_RAINY, WeatherCondition.HAIL ->
                intArrayOf(0xFF26384A.toInt(), 0xFF718597.toInt(), 0xFFB9C7D1.toInt())
            else -> intArrayOf(0xFF101A2D.toInt(), 0xFF223957.toInt(), 0xFF5D5265.toInt())
        }
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        when {
            profile.sunlight -> drawSunlight(canvas, elapsedSeconds)
            profile.stars -> drawNightSky(canvas, elapsedSeconds, profile.shootingStars)
            profile.rainIntensity > 0 -> {
                drawMovingClouds(canvas, elapsedSeconds, profile.cloudSpeedMultiplier, condition == WeatherCondition.PARTLY_CLOUDY)
                drawRain(canvas, elapsedSeconds, heavy = profile.rainIntensity == 2, lightning = profile.lightning)
            }
            profile.snow -> {
                drawMovingClouds(canvas, elapsedSeconds, profile.cloudSpeedMultiplier, partlyCloudy = false)
                drawSnow(canvas, elapsedSeconds)
            }
            profile.fog -> drawFog(canvas, elapsedSeconds)
            profile.clouds -> drawMovingClouds(
                canvas,
                elapsedSeconds,
                profile.cloudSpeedMultiplier,
                condition == WeatherCondition.PARTLY_CLOUDY,
            )
        }
        drawFixedTerrain(canvas)
    }

    private fun drawSunlight(canvas: Canvas, elapsedSeconds: Float) {
        val pulse = 0.92f + sin(elapsedSeconds * 0.35f) * 0.04f
        paint.shader = RadialGradient(
            width * 0.82f,
            height * 0.16f,
            width * 0.30f * pulse,
            intArrayOf(0x70FFF2B0, 0x18FFF2B0, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawNightSky(canvas: Canvas, elapsedSeconds: Float, shootingStars: Int) {
        repeat(26) { index ->
            val twinkle = ((sin(elapsedSeconds * 0.7f + index * 1.9f) + 1f) * 0.5f)
            paint.color = Color.argb((55 + twinkle * 105).toInt(), 236, 243, 255)
            val x = width * ((index * 47 % 101) / 100f)
            val y = height * (0.035f + (index * 29 % 46) / 100f)
            canvas.drawCircle(x, y, dp(if (index % 5 == 0) 1.8f else 1.1f), paint)
        }
        if (shootingStars > 0) {
            drawShootingStar(canvas, elapsedSeconds, phaseOffset = 0f, startX = 0.68f, startY = 0.10f)
        }
        if (shootingStars > 1) {
            drawShootingStar(canvas, elapsedSeconds, phaseOffset = 14f, startX = 0.25f, startY = 0.20f)
        }
    }

    private fun drawShootingStar(
        canvas: Canvas,
        elapsedSeconds: Float,
        phaseOffset: Float,
        startX: Float,
        startY: Float,
    ) {
        val phase = (elapsedSeconds + phaseOffset) % SHOOTING_STAR_CYCLE_SECONDS
        if (phase > SHOOTING_STAR_DURATION_SECONDS) return
        val progress = phase / SHOOTING_STAR_DURATION_SECONDS
        val opacity = (sin(progress * Math.PI) * 190).toInt().coerceIn(0, 190)
        val headX = width * (startX + progress * 0.16f)
        val headY = height * (startY + progress * 0.10f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(2f)
        paint.shader = LinearGradient(
            headX - width * 0.075f,
            headY - height * 0.047f,
            headX,
            headY,
            Color.TRANSPARENT,
            Color.argb(opacity, 245, 249, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawLine(headX - width * 0.075f, headY - height * 0.047f, headX, headY, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawMovingClouds(
        canvas: Canvas,
        elapsedSeconds: Float,
        speedMultiplier: Float,
        partlyCloudy: Boolean,
    ) {
        val speed = 7f * speedMultiplier
        repeat(4) { index ->
            val cloudWidth = dp(170f + index * 28f)
            val span = width + cloudWidth * 2f
            val x = ((width * (index * 0.29f) + elapsedSeconds * dp(speed) * (1f + index * 0.08f)) % span) - cloudWidth
            val y = height * (0.08f + (index % 3) * 0.13f)
            val alpha = if (partlyCloudy) 32 + index * 5 else 48 + index * 7
            drawSceneCloud(canvas, x, y, cloudWidth, alpha)
        }
    }

    private fun drawSceneCloud(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, alpha: Int) {
        paint.color = Color.argb(alpha.coerceIn(0, 255), 238, 243, 248)
        canvas.drawCircle(centerX - radius * 0.42f, centerY, radius * 0.33f, paint)
        canvas.drawCircle(centerX, centerY - radius * 0.15f, radius * 0.43f, paint)
        canvas.drawCircle(centerX + radius * 0.46f, centerY, radius * 0.31f, paint)
        canvas.drawRoundRect(
            RectF(centerX - radius * 0.72f, centerY, centerX + radius * 0.75f, centerY + radius * 0.30f),
            radius * 0.16f,
            radius * 0.16f,
            paint,
        )
    }

    private fun drawRain(canvas: Canvas, elapsedSeconds: Float, heavy: Boolean, lightning: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(if (heavy) 1.7f else 1.25f)
        paint.color = if (heavy) 0x728FCBFF else 0x528FCBFF
        repeat(if (heavy) 44 else 28) { index ->
            val x = width * ((index * 37 % 101) / 100f)
            val y = height * (((index * 23 % 100) / 100f + elapsedSeconds * (0.34f + index % 5 * 0.018f)) % 1f)
            canvas.drawLine(x, y, x - dp(7f), y + dp(if (heavy) 28f else 20f), paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        if (lightning) {
            val phase = elapsedSeconds % 12f
            if (phase < 0.12f) {
                paint.color = Color.argb(((0.12f - phase) / 0.12f * 42).toInt(), 225, 237, 255)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    private fun drawSnow(canvas: Canvas, elapsedSeconds: Float) {
        repeat(34) { index ->
            val drift = sin(elapsedSeconds * 0.55f + index) * width * 0.006f
            val x = width * ((index * 43 % 103) / 102f) + drift
            val y = height * (((index * 31 % 100) / 100f + elapsedSeconds * (0.035f + index % 4 * 0.005f)) % 1f)
            paint.color = Color.argb(95 + index % 4 * 22, 245, 249, 255)
            canvas.drawCircle(x, y, dp(1.4f + index % 3 * 0.5f), paint)
        }
    }

    private fun drawFog(canvas: Canvas, elapsedSeconds: Float) {
        repeat(4) { index ->
            val bandWidth = width * 0.70f
            val span = width + bandWidth
            val x = ((index * width * 0.31f + elapsedSeconds * dp(5f + index)) % span) - bandWidth
            paint.shader = LinearGradient(
                x,
                0f,
                x + bandWidth,
                0f,
                intArrayOf(Color.TRANSPARENT, 0x38EEF3F6, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, height * (0.14f + index * 0.16f), width.toFloat(), height * (0.24f + index * 0.16f), paint)
            paint.shader = null
        }
    }

    private fun drawFixedTerrain(canvas: Canvas) {
        terrainPath.reset()
        terrainPath.moveTo(0f, height.toFloat())
        terrainPath.lineTo(0f, height * 0.82f)
        terrainPath.lineTo(width * 0.12f, height * 0.72f)
        terrainPath.lineTo(width * 0.23f, height * 0.84f)
        terrainPath.lineTo(width * 0.40f, height * 0.76f)
        terrainPath.lineTo(width * 0.58f, height * 0.87f)
        terrainPath.lineTo(width * 0.76f, height * 0.78f)
        terrainPath.lineTo(width.toFloat(), height * 0.85f)
        terrainPath.lineTo(width.toFloat(), height.toFloat())
        terrainPath.close()
        paint.color = 0x4D111A24
        canvas.drawPath(terrainPath, paint)

        terrainPath.reset()
        terrainPath.moveTo(0f, height.toFloat())
        terrainPath.lineTo(0f, height * 0.91f)
        terrainPath.cubicTo(width * 0.22f, height * 0.82f, width * 0.42f, height * 0.96f, width * 0.62f, height * 0.88f)
        terrainPath.cubicTo(width * 0.79f, height * 0.82f, width * 0.90f, height * 0.92f, width.toFloat(), height * 0.87f)
        terrainPath.lineTo(width.toFloat(), height.toFloat())
        terrainPath.close()
        paint.color = 0x8F0D151D.toInt()
        canvas.drawPath(terrainPath, paint)
    }

    private fun drawEmptyState(canvas: Canvas, message: String) {
        drawCard(canvas, RectF(width * 0.23f, height * 0.30f, width * 0.77f, height * 0.69f))
        drawWeatherIcon(canvas, WeatherCondition.CLOUDY, width * 0.50f, height * 0.41f, dp(38f))
        drawCenteredText(canvas, message, width * 0.50f, height * 0.53f, 28f, Color.WHITE, true)
        val detail = if (message.contains("sign-in")) "Press ★ to connect securely" else "The last forecast will return automatically"
        drawCenteredText(canvas, detail, width * 0.50f, height * 0.60f, 19f, MUTED)
    }

    private fun drawCurrent(canvas: Canvas) {
        val card = RectF(width * 0.05f, height * 0.17f, width * 0.42f, height * 0.55f)
        drawCard(canvas, card)
        drawText(canvas, "CURRENT CONDITIONS", card.left + dp(24f), card.top + dp(36f), 15f, MUTED, true)
        val condition = presentation.sceneCondition
        drawWeatherIcon(canvas, condition, card.left + card.width() * 0.18f, card.top + card.height() * 0.42f, dp(38f))
        drawText(canvas, presentation.temperature, card.left + card.width() * 0.34f, card.top + card.height() * 0.48f, 62f, Color.WHITE, true)
        drawText(canvas, presentation.condition, card.left + card.width() * 0.34f, card.top + card.height() * 0.61f, 23f, Color.WHITE, true)

        val metrics = presentation.metrics.take(4)
        metrics.forEachIndexed { index, metric ->
            val x = card.left + dp(24f) + index * (card.width() - dp(48f)) / maxOf(metrics.size, 1)
            drawMetric(canvas, metric, x, card.bottom - dp(58f))
        }
    }

    private fun drawDaily(canvas: Canvas) {
        val card = RectF(width * 0.45f, height * 0.17f, width * 0.95f, height * 0.55f)
        drawCard(canvas, card)
        drawText(canvas, "DAILY FORECAST", card.left + dp(24f), card.top + dp(36f), 15f, MUTED, true)
        val days = presentation.daily.take(7)
        days.forEachIndexed { index, item ->
            val center = card.left + card.width() * (index + 0.5f) / maxOf(days.size, 1)
            drawCenteredText(canvas, item.time, center, card.top + card.height() * 0.29f, 17f, MUTED, true)
            drawWeatherIcon(canvas, item.condition, center, card.top + card.height() * 0.49f, dp(20f))
            drawCenteredText(canvas, "${item.temperature}°", center, card.top + card.height() * 0.72f, 24f, Color.WHITE, true)
            item.lowTemperature?.let {
                drawCenteredText(canvas, "$it°", center, card.top + card.height() * 0.84f, 17f, 0xFFADB7C6.toInt())
            }
        }
    }

    private fun drawHourly(canvas: Canvas) {
        val card = RectF(width * 0.05f, height * 0.59f, width * 0.95f, height * 0.91f)
        drawCard(canvas, card)
        val pageCount = hourlyPager.pageCount(presentation.hourly)
        val pageIndex = currentHourlyPageIndex(pageCount, SystemClock.uptimeMillis())
        val items = hourlyPager.page(presentation.hourly, pageIndex)
        val normalizedPage = Math.floorMod(pageIndex, pageCount)
        val rangeStart = normalizedPage * WeatherRenderCadence.HOURLY_PAGE_SIZE + 1
        val rangeEnd = minOf(rangeStart + items.size - 1, presentation.hourly.size)
        drawText(canvas, "HOURLY FORECAST", card.left + dp(24f), card.top + dp(36f), 15f, MUTED, true)
        if (presentation.hourly.isNotEmpty()) {
            drawText(canvas, "$rangeStart–$rangeEnd of ${presentation.hourly.size} hours", card.right - dp(190f), card.top + dp(36f), 15f, MUTED)
        }
        items.forEachIndexed { index, item ->
            val center = card.left + card.width() * (index + 0.5f) / maxOf(items.size, 1)
            drawCenteredText(canvas, item.time, center, card.top + card.height() * 0.34f, 15f, MUTED, true)
            drawWeatherIcon(canvas, item.condition, center, card.top + card.height() * 0.56f, dp(17f))
            drawCenteredText(canvas, "${item.temperature}°", center, card.top + card.height() * 0.79f, 22f, Color.WHITE, true)
            item.precipitation?.takeUnless { it == "0%" }?.let { precipitation ->
                drawCenteredText(canvas, precipitation, center, card.top + card.height() * 0.91f, 13f, 0xFF91C9FF.toInt(), true)
            }
        }
    }

    private fun currentHourlyPageIndex(pageCount: Int, now: Long): Int = if (pageCount <= 1) {
        0
    } else {
        pageAnchorIndex + ((now - pageEpochMillis) / WeatherRenderCadence.HOURLY_PAGE_DURATION_MILLIS).toInt()
    }

    private fun drawMetric(canvas: Canvas, metric: WeatherMetric, x: Float, y: Float) {
        drawText(canvas, metric.label, x, y, 14f, MUTED)
        drawText(canvas, metric.value, x, y + dp(25f), 18f, Color.WHITE, true)
    }

    private fun drawWeatherIcon(canvas: Canvas, condition: WeatherCondition, centerX: Float, centerY: Float, radius: Float) {
        when (condition) {
            WeatherCondition.SUNNY -> drawSun(canvas, centerX, centerY, radius)
            WeatherCondition.CLEAR_NIGHT -> drawMoon(canvas, centerX, centerY, radius)
            WeatherCondition.RAINY, WeatherCondition.POURING, WeatherCondition.LIGHTNING_RAINY -> {
                drawCloud(canvas, centerX, centerY - radius * 0.12f, radius)
                paint.color = 0xFF8BCBFF.toInt()
                paint.strokeWidth = dp(2f)
                repeat(3) { index ->
                    val x = centerX - radius * 0.55f + index * radius * 0.55f
                    canvas.drawLine(x, centerY + radius * 0.55f, x - radius * 0.14f, centerY + radius, paint)
                }
            }
            WeatherCondition.CLOUDY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.FOG -> drawCloud(canvas, centerX, centerY, radius)
            else -> {
                paint.color = 0xFFE2E8F0.toInt()
                canvas.drawCircle(centerX, centerY, radius * 0.72f, paint)
            }
        }
    }

    private fun drawSun(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        paint.color = 0xFFFFDA59.toInt()
        canvas.drawCircle(centerX, centerY, radius * 0.72f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            canvas.drawLine(
                centerX + (cos(angle) * radius * 0.95f).toFloat(),
                centerY + (sin(angle) * radius * 0.95f).toFloat(),
                centerX + (cos(angle) * radius * 1.25f).toFloat(),
                centerY + (sin(angle) * radius * 1.25f).toFloat(),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawMoon(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        paint.color = 0xFFFFE78A.toInt()
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.color = 0xFF1C2A40.toInt()
        canvas.drawCircle(centerX + radius * 0.42f, centerY - radius * 0.18f, radius * 0.84f, paint)
    }

    private fun drawCloud(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        paint.color = 0xFFE5E9F0.toInt()
        canvas.drawCircle(centerX - radius * 0.42f, centerY, radius * 0.48f, paint)
        canvas.drawCircle(centerX, centerY - radius * 0.20f, radius * 0.62f, paint)
        canvas.drawCircle(centerX + radius * 0.48f, centerY, radius * 0.44f, paint)
        canvas.drawRoundRect(
            RectF(centerX - radius * 0.82f, centerY, centerX + radius * 0.84f, centerY + radius * 0.48f),
            radius * 0.22f,
            radius * 0.22f,
            paint,
        )
    }

    private fun drawCard(canvas: Canvas, rect: RectF) {
        paint.color = 0xB31A1E24.toInt()
        canvas.drawRoundRect(rect, dp(20f), dp(20f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x4DFFFFFF
        canvas.drawRoundRect(rect, dp(20f), dp(20f), paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawCenteredText(canvas: Canvas, text: String, centerX: Float, baseline: Float, sizeSp: Float, color: Int, medium: Boolean = false) {
        configureText(sizeSp, color, medium)
        canvas.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, baseline: Float, sizeSp: Float, color: Int, medium: Boolean = false) {
        configureText(sizeSp, color, medium)
        canvas.drawText(text, x, baseline, paint)
    }

    private fun configureText(sizeSp: Float, color: Int, medium: Boolean) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        paint.typeface = android.graphics.Typeface.create(
            "sans",
            if (medium) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val SHOOTING_STAR_CYCLE_SECONDS = 30f
        const val SHOOTING_STAR_DURATION_SECONDS = 1.35f
        const val MUTED = 0xFFD1D7E0.toInt()

        fun previewPresentation(): WeatherPresentation = WeatherPresentation(
            temperature = "79°F",
            condition = "Clear night",
            sceneCondition = WeatherCondition.CLEAR_NIGHT,
            status = "Preview · live connection pending",
            metrics = listOf(
                WeatherMetric("Humidity", "67%"),
                WeatherMetric("Wind", "3.1 mph"),
                WeatherMetric("Pressure", "29.93 inHg"),
            ),
            daily = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").mapIndexed { index, day ->
                WeatherForecastItem(
                    time = day,
                    condition = if (index == 0 || index >= 4) WeatherCondition.CLEAR_NIGHT else WeatherCondition.SUNNY,
                    temperature = listOf("94", "96", "99", "101", "90", "88", "91")[index],
                    lowTemperature = listOf("76", "73", "74", "83", "76", "70", "71")[index],
                    precipitation = null,
                )
            },
            hourly = (0 until 24).map { index ->
                val hour = (22 + index) % 24
                WeatherForecastItem(
                    time = when (hour) {
                        0 -> "Midnight"
                        12 -> "Noon"
                        in 1..11 -> "$hour AM"
                        else -> "${if (hour == 12) 12 else hour - 12} PM"
                    },
                    condition = if (index in 6..8) WeatherCondition.PARTLY_CLOUDY else WeatherCondition.CLEAR_NIGHT,
                    temperature = (77 - index / 2).toString(),
                    lowTemperature = null,
                    precipitation = if (index == 8) "20%" else "0%",
                )
            },
        )
    }
}
