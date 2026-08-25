package com.wyattfleming.frameos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import com.wyattfleming.frameos.navigation.FrameMode
import kotlin.math.min

class DemoContentView(context: Context) : View(context) {
    var mode: FrameMode = FrameMode.PHOTOS
        set(value) {
            field = value
            contentDescription = "${value.label} preview"
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Photos preview"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            FrameMode.PHOTOS -> drawPhotos(canvas)
            FrameMode.HOME -> drawHome(canvas)
            FrameMode.WEATHER -> drawWeather(canvas)
            FrameMode.CAMERAS -> drawCameras(canvas)
            FrameMode.CALENDAR -> drawCalendar(canvas)
        }
    }

    private fun drawPhotos(canvas: Canvas) {
        val sky = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(Color.rgb(34, 55, 92), Color.rgb(218, 142, 111), Color.rgb(37, 51, 48)),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.shader = sky
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        paint.color = Color.argb(205, 255, 218, 153)
        canvas.drawCircle(width * 0.78f, height * 0.28f, min(width, height) * 0.075f, paint)

        drawMountain(canvas, height * 0.82f, height * 0.30f, Color.rgb(56, 73, 78))
        drawMountain(canvas, height * 0.92f, height * 0.24f, Color.rgb(35, 55, 51))

        paint.color = Color.argb(165, 12, 15, 18)
        canvas.drawRoundRect(
            RectF(width * 0.045f, height * 0.78f, width * 0.39f, height * 0.91f),
            dp(18f),
            dp(18f),
            paint,
        )
        drawText(canvas, "Saturday evening", width * 0.07f, height * 0.845f, 31f, Color.WHITE, true)
        drawText(canvas, "A quiet moment from the family library", width * 0.07f, height * 0.89f, 19f, 0xFFD8D5CF.toInt())
    }

    private fun drawHome(canvas: Canvas) {
        canvas.drawColor(Color.rgb(22, 29, 42))
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(28, 43, 69), Color.rgb(71, 89, 112), Color.rgb(173, 129, 103)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        drawText(canvas, "Good morning", width * 0.055f, height * 0.13f, 42f, Color.WHITE, true)
        drawText(canvas, "Monday, August 24", width * 0.057f, height * 0.185f, 23f, 0xFFD9DEE7.toInt())

        drawCard(canvas, RectF(width * 0.05f, height * 0.25f, width * 0.48f, height * 0.82f))
        drawText(canvas, "At a glance", width * 0.078f, height * 0.32f, 27f, Color.WHITE, true)
        drawMetric(canvas, "Next event", "Family dinner  ·  6:30 PM", width * 0.078f, height * 0.42f)
        drawMetric(canvas, "House", "Everything looks good", width * 0.078f, height * 0.55f)
        drawMetric(canvas, "Energy", "Using less than yesterday", width * 0.078f, height * 0.68f)

        drawCard(canvas, RectF(width * 0.52f, height * 0.25f, width * 0.95f, height * 0.82f))
        drawText(canvas, "Weather", width * 0.55f, height * 0.32f, 27f, Color.WHITE, true)
        drawText(canvas, "72°", width * 0.55f, height * 0.49f, 78f, Color.WHITE, true)
        drawText(canvas, "Partly cloudy", width * 0.71f, height * 0.45f, 25f, Color.WHITE, true)
        drawText(canvas, "High 78°   Low 59°", width * 0.71f, height * 0.50f, 20f, 0xFFD9DEE7.toInt())
        drawMetric(canvas, "Sunrise", "6:28 AM", width * 0.55f, height * 0.65f)
        drawMetric(canvas, "Sunset", "7:46 PM", width * 0.75f, height * 0.65f)
    }

    private fun drawWeather(canvas: Canvas) {
        paint.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(18, 27, 43), Color.rgb(35, 52, 77), Color.rgb(92, 72, 76)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        drawText(canvas, "Weather", width * 0.055f, height * 0.105f, 40f, Color.WHITE, true)
        drawText(canvas, "Home  ·  Updated 8:42 PM", width * 0.055f, height * 0.15f, 20f, 0xFFD1D7E0.toInt())

        drawMoon(canvas, width * 0.105f, height * 0.31f, dp(46f))
        drawText(canvas, "79°", width * 0.165f, height * 0.35f, 82f, Color.WHITE, true)
        drawText(canvas, "Clear night", width * 0.165f, height * 0.405f, 26f, Color.WHITE, true)
        drawText(canvas, "High 94°  ·  Low 76°", width * 0.165f, height * 0.45f, 19f, 0xFFD1D7E0.toInt())

        drawCard(canvas, RectF(width * 0.05f, height * 0.50f, width * 0.42f, height * 0.66f))
        drawText(canvas, "CURRENT CONDITIONS", width * 0.07f, height * 0.545f, 15f, 0xFFB7C0CE.toInt(), true)
        drawWeatherMetric(canvas, "Humidity", "67%", width * 0.07f, height * 0.585f)
        drawWeatherMetric(canvas, "Wind", "3 mph SSW", width * 0.185f, height * 0.585f)
        drawWeatherMetric(canvas, "Pressure", "29.93 inHg", width * 0.31f, height * 0.585f)

        drawCard(canvas, RectF(width * 0.05f, height * 0.69f, width * 0.42f, height * 0.88f))
        drawText(canvas, "PAST 24 HOURS", width * 0.07f, height * 0.735f, 15f, 0xFFB7C0CE.toInt(), true)
        val history = RectF(width * 0.07f, height * 0.775f, width * 0.40f, height * 0.815f)
        paint.color = 0xFF6B49A8.toInt()
        canvas.drawRoundRect(RectF(history.left, history.top, history.left + history.width() * 0.42f, history.bottom), dp(7f), dp(7f), paint)
        paint.color = 0xFF7892A2.toInt()
        canvas.drawRect(history.left + history.width() * 0.42f, history.top, history.left + history.width() * 0.55f, history.bottom, paint)
        paint.color = 0xFFF2B82C.toInt()
        canvas.drawRoundRect(RectF(history.left + history.width() * 0.55f, history.top, history.right, history.bottom), dp(7f), dp(7f), paint)
        drawText(canvas, "12 AM", history.left, height * 0.855f, 15f, 0xFFB7C0CE.toInt())
        drawText(canvas, "6 AM", history.left + history.width() * 0.31f, height * 0.855f, 15f, 0xFFB7C0CE.toInt())
        drawText(canvas, "Noon", history.left + history.width() * 0.58f, height * 0.855f, 15f, 0xFFB7C0CE.toInt())
        drawText(canvas, "Now", history.right - dp(34f), height * 0.855f, 15f, 0xFFB7C0CE.toInt())

        val forecastCard = RectF(width * 0.46f, height * 0.16f, width * 0.95f, height * 0.88f)
        drawCard(canvas, forecastCard)
        drawText(canvas, "Daily forecast", width * 0.49f, height * 0.225f, 27f, Color.WHITE, true)
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
        val highs = listOf("94°", "96°", "99°", "101°", "90°")
        val lows = listOf("76°", "73°", "74°", "83°", "76°")
        val dailyLeft = width * 0.49f
        val dailyWidth = width * 0.43f
        days.forEachIndexed { index, day ->
            val center = dailyLeft + dailyWidth * (index + 0.5f) / days.size
            drawCenteredText(canvas, day, center, height * 0.295f, 18f, 0xFFD1D7E0.toInt(), true)
            drawSun(canvas, center, height * 0.365f, dp(if (index == 0 || index == 3) 21f else 18f))
            drawCenteredText(canvas, highs[index], center, height * 0.445f, 27f, Color.WHITE, true)
            drawCenteredText(canvas, lows[index], center, height * 0.485f, 18f, 0xFFADB7C6.toInt())
        }

        paint.color = 0x33FFFFFF
        canvas.drawRect(width * 0.49f, height * 0.535f, width * 0.92f, height * 0.537f, paint)
        drawText(canvas, "Hourly forecast", width * 0.49f, height * 0.605f, 27f, Color.WHITE, true)
        val hours = listOf("Now", "10 PM", "Midnight", "2 AM", "4 AM")
        val temperatures = listOf("79°", "78°", "76°", "74°", "73°")
        hours.forEachIndexed { index, hour ->
            val center = dailyLeft + dailyWidth * (index + 0.5f) / hours.size
            drawCenteredText(canvas, hour, center, height * 0.675f, 16f, 0xFFD1D7E0.toInt(), true)
            drawMoon(canvas, center, height * 0.735f, dp(18f))
            drawCenteredText(canvas, temperatures[index], center, height * 0.81f, 25f, Color.WHITE, true)
        }

        drawText(canvas, "Sunrise 6:28 AM", width * 0.055f, height * 0.945f, 17f, 0xFFD1D7E0.toInt())
        drawText(canvas, "Sunset 7:46 PM", width * 0.28f, height * 0.945f, 17f, 0xFFD1D7E0.toInt())
    }

    private fun drawCameras(canvas: Canvas) {
        canvas.drawColor(Color.rgb(14, 17, 22))
        drawText(canvas, "Cameras", width * 0.05f, height * 0.11f, 38f, Color.WHITE, true)
        drawText(canvas, "Select a camera to start its live view", width * 0.05f, height * 0.16f, 20f, 0xFFBFC7D4.toInt())
        val gap = width * 0.018f
        val left = width * 0.05f
        val top = height * 0.22f
        val tileWidth = (width * 0.90f - gap) / 2f
        val tileHeight = (height * 0.70f - gap) / 2f
        val labels = listOf("Front yard", "Driveway", "Backyard", "Side gate")
        labels.forEachIndexed { index, label ->
            val column = index % 2
            val row = index / 2
            val rect = RectF(
                left + column * (tileWidth + gap),
                top + row * (tileHeight + gap),
                left + column * (tileWidth + gap) + tileWidth,
                top + row * (tileHeight + gap) + tileHeight,
            )
            paint.shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                Color.rgb(52 + index * 8, 65 + index * 5, 74 + index * 7),
                Color.rgb(21, 27, 31),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(rect, dp(16f), dp(16f), paint)
            paint.shader = null
            drawText(canvas, label, rect.left + dp(24f), rect.bottom - dp(27f), 23f, Color.WHITE, true)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = 0x99FFFFFF.toInt()
            canvas.drawCircle(rect.centerX(), rect.centerY() - dp(8f), dp(34f), paint)
            canvas.drawCircle(rect.centerX(), rect.centerY() - dp(8f), dp(12f), paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawCalendar(canvas: Canvas) {
        canvas.drawColor(0xFFF1EEE7.toInt())
        drawText(canvas, "August 2026", width * 0.055f, height * 0.105f, 40f, 0xFF282827.toInt(), true)
        drawText(canvas, "Family calendar", width * 0.78f, height * 0.10f, 22f, 0xFF5D6570.toInt())
        val left = width * 0.055f
        val right = width * 0.945f
        val top = height * 0.19f
        val bottom = height * 0.91f
        val cellWidth = (right - left) / 7f
        val cellHeight = (bottom - top) / 5f
        val weekdays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        weekdays.forEachIndexed { index, day ->
            drawText(canvas, day, left + index * cellWidth + dp(10f), top - dp(18f), 18f, 0xFF68707A.toInt(), true)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = 0x33464A4E
        for (column in 0..7) canvas.drawLine(left + column * cellWidth, top, left + column * cellWidth, bottom, paint)
        for (row in 0..5) canvas.drawLine(left, top + row * cellHeight, right, top + row * cellHeight, paint)
        paint.style = Paint.Style.FILL
        for (day in 1..31) {
            val slot = day + 5
            val column = slot % 7
            val row = slot / 7
            drawText(canvas, day.toString(), left + column * cellWidth + dp(11f), top + row * cellHeight + dp(27f), 17f, 0xFF343738.toInt(), true)
        }
        drawEvent(canvas, left + cellWidth * 1 + dp(8f), top + cellHeight * 1 + dp(40f), cellWidth - dp(16f), "Library books")
        drawEvent(canvas, left + cellWidth * 4 + dp(8f), top + cellHeight * 2 + dp(40f), cellWidth - dp(16f), "Family dinner")
        drawEvent(canvas, left + cellWidth * 6 + dp(8f), top + cellHeight * 3 + dp(40f), cellWidth - dp(16f), "Soccer")
    }

    private fun drawMountain(canvas: Canvas, baseline: Float, peakHeight: Float, color: Int) {
        path.reset()
        path.moveTo(0f, baseline)
        path.lineTo(width * 0.18f, baseline - peakHeight * 0.55f)
        path.lineTo(width * 0.34f, baseline - peakHeight)
        path.lineTo(width * 0.51f, baseline - peakHeight * 0.35f)
        path.lineTo(width * 0.71f, baseline - peakHeight * 0.75f)
        path.lineTo(width.toFloat(), baseline - peakHeight * 0.2f)
        path.lineTo(width.toFloat(), height.toFloat())
        path.lineTo(0f, height.toFloat())
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
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

    private fun drawMetric(canvas: Canvas, label: String, value: String, x: Float, y: Float) {
        drawText(canvas, label, x, y, 18f, 0xFFBFC7D4.toInt(), true)
        drawText(canvas, value, x, y + dp(32f), 23f, Color.WHITE)
    }

    private fun drawEvent(canvas: Canvas, x: Float, y: Float, eventWidth: Float, label: String) {
        paint.color = 0xFFCEDCFA.toInt()
        canvas.drawRoundRect(RectF(x, y, x + eventWidth, y + dp(42f)), dp(8f), dp(8f), paint)
        drawText(canvas, label, x + dp(10f), y + dp(27f), 16f, 0xFF273A63.toInt(), true)
    }

    private fun drawWeatherMetric(canvas: Canvas, label: String, value: String, x: Float, y: Float) {
        drawText(canvas, label, x, y, 15f, 0xFFB7C0CE.toInt())
        drawText(canvas, value, x, y + dp(26f), 19f, Color.WHITE, true)
    }

    private fun drawSun(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        paint.color = 0xFFFFDA59.toInt()
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            val inner = radius * 1.35f
            val outer = radius * 1.7f
            canvas.drawLine(
                centerX + (kotlin.math.cos(angle) * inner).toFloat(),
                centerY + (kotlin.math.sin(angle) * inner).toFloat(),
                centerX + (kotlin.math.cos(angle) * outer).toFloat(),
                centerY + (kotlin.math.sin(angle) * outer).toFloat(),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawMoon(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        paint.color = 0xFFFFE78A.toInt()
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.color = Color.rgb(29, 38, 55)
        canvas.drawCircle(centerX + radius * 0.42f, centerY - radius * 0.18f, radius * 0.84f, paint)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baseline: Float,
        sizeSp: Float,
        color: Int,
        medium: Boolean = false,
    ) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        paint.typeface = android.graphics.Typeface.create("sans", if (medium) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        drawText(canvas, text, centerX - paint.measureText(text) / 2f, baseline, sizeSp, color, medium)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        sizeSp: Float,
        color: Int,
        medium: Boolean = false,
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sizeSp,
            resources.displayMetrics,
        )
        paint.typeface = android.graphics.Typeface.create("sans", if (medium) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        canvas.drawText(text, x, baseline, paint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
