package com.wyattfleming.frameos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
import kotlin.math.cos
import kotlin.math.sin

class WeatherContentView(context: Context) : View(context) {
    var presentation: WeatherPresentation = previewPresentation()
        set(value) {
            field = value
            pageEpochMillis = SystemClock.uptimeMillis()
            contentDescription = value.emptyMessage ?: "${value.condition}, ${value.temperature}. ${value.status}"
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hourlyPager = WeatherHourlyPager(HOURLY_SLOTS_PER_PAGE)
    private var pageEpochMillis = SystemClock.uptimeMillis()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Clear night, 79°F. Preview weather"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBackground(canvas)
        drawText(canvas, "Weather", width * 0.05f, height * 0.10f, 40f, Color.WHITE, true)

        val emptyMessage = presentation.emptyMessage
        if (emptyMessage != null) {
            drawEmptyState(canvas, emptyMessage)
            return
        }

        drawText(canvas, presentation.status, width * 0.05f, height * 0.145f, 19f, MUTED)
        drawCurrent(canvas)
        drawDaily(canvas)
        drawHourly(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        val colors = when (presentation.hourly.firstOrNull()?.condition) {
            WeatherCondition.SUNNY -> intArrayOf(0xFF226AA0.toInt(), 0xFF69A9C6.toInt(), 0xFFD9A466.toInt())
            WeatherCondition.RAINY, WeatherCondition.POURING, WeatherCondition.LIGHTNING_RAINY ->
                intArrayOf(0xFF182431.toInt(), 0xFF33495B.toInt(), 0xFF48545D.toInt())
            WeatherCondition.CLOUDY, WeatherCondition.PARTLY_CLOUDY ->
                intArrayOf(0xFF26374B.toInt(), 0xFF61758A.toInt(), 0xFF8B8584.toInt())
            else -> intArrayOf(0xFF101A2D.toInt(), 0xFF223957.toInt(), 0xFF5D5265.toInt())
        }
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = 0x12FFFFFF
        repeat(18) { index ->
            val x = width * ((index * 47 % 100) / 100f)
            val y = height * (0.04f + (index * 29 % 40) / 100f)
            canvas.drawCircle(x, y, dp(if (index % 4 == 0) 1.8f else 1.1f), paint)
        }
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
        val condition = presentation.hourly.firstOrNull()?.condition ?: WeatherCondition.UNKNOWN
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
        val pageIndex = if (pageCount <= 1) 0 else ((SystemClock.uptimeMillis() - pageEpochMillis) / PAGE_DURATION_MILLIS).toInt()
        val items = hourlyPager.page(presentation.hourly, pageIndex)
        val normalizedPage = Math.floorMod(pageIndex, pageCount)
        val rangeStart = normalizedPage * HOURLY_SLOTS_PER_PAGE + 1
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
        if (pageCount > 1 && visibility == VISIBLE) postInvalidateDelayed(1_000L)
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
        const val HOURLY_SLOTS_PER_PAGE = 12
        const val PAGE_DURATION_MILLIS = 10_000L
        const val MUTED = 0xFFD1D7E0.toInt()

        fun previewPresentation(): WeatherPresentation = WeatherPresentation(
            temperature = "79°F",
            condition = "Clear night",
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
