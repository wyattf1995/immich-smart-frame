package com.wyattfleming.frameos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wyattfleming.frameos.navigation.FrameMode

/** Dependency-free Canvas symbols for the touch dashboard and rail. */
internal class TouchModeIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    constructor(context: Context, mode: FrameMode?) : this(context) {
        this.mode = mode
    }

    var mode: FrameMode? = null
    var tint: Int = 0xff1f7074.toInt()
        set(value) { field = value; invalidate() }
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val p = w * .22f
        paint.color = tint
        when (mode) {
            FrameMode.PHOTOS -> {
                bounds.set(p, p, w - p, h - p)
                canvas.drawRoundRect(bounds, p * .25f, p * .25f, paint)
                canvas.drawCircle(w * .66f, h * .40f, p * .16f, paint)
                canvas.drawLine(w * .31f, h * .67f, w * .46f, h * .51f, paint)
                canvas.drawLine(w * .46f, h * .51f, w * .70f, h * .70f, paint)
            }
            FrameMode.HOME -> {
                canvas.drawLine(w * .25f, h * .48f, w * .50f, h * .27f, paint)
                canvas.drawLine(w * .50f, h * .27f, w * .75f, h * .48f, paint)
                canvas.drawLine(w * .31f, h * .45f, w * .31f, h * .73f, paint)
                canvas.drawLine(w * .69f, h * .45f, w * .69f, h * .73f, paint)
                canvas.drawLine(w * .31f, h * .73f, w * .69f, h * .73f, paint)
            }
            FrameMode.WEATHER -> {
                canvas.drawCircle(w * .45f, h * .46f, w * .16f, paint)
                canvas.drawLine(w * .45f, h * .20f, w * .45f, h * .12f, paint)
                canvas.drawLine(w * .25f, h * .65f, w * .75f, h * .65f, paint)
            }
            FrameMode.CAMERAS -> {
                bounds.set(w * .20f, h * .34f, w * .68f, h * .70f)
                canvas.drawRoundRect(bounds, p * .2f, p * .2f, paint)
                canvas.drawCircle(w * .45f, h * .52f, w * .10f, paint)
                canvas.drawLine(w * .68f, h * .43f, w * .81f, h * .34f, paint)
                canvas.drawLine(w * .81f, h * .34f, w * .81f, h * .70f, paint)
                canvas.drawLine(w * .81f, h * .70f, w * .68f, h * .61f, paint)
            }
            FrameMode.CALENDAR -> {
                bounds.set(w * .23f, h * .22f, w * .77f, h * .77f)
                canvas.drawRoundRect(bounds, p * .2f, p * .2f, paint)
                canvas.drawLine(w * .23f, h * .40f, w * .77f, h * .40f, paint)
                canvas.drawLine(w * .38f, h * .16f, w * .38f, h * .30f, paint)
                canvas.drawLine(w * .62f, h * .16f, w * .62f, h * .30f, paint)
            }
            FrameMode.BIRDS -> {
                bounds.set(w * .18f, h * .35f, w * .50f, h * .65f)
                canvas.drawArc(bounds, 205f, 130f, false, paint)
                bounds.set(w * .50f, h * .35f, w * .82f, h * .65f)
                canvas.drawArc(bounds, 205f, 130f, false, paint)
            }
            null -> {
                canvas.drawCircle(w / 2f, h / 2f, p, paint)
                canvas.drawLine(w / 2f, h * .32f, w / 2f, h * .68f, paint)
                canvas.drawLine(w * .32f, h / 2f, w * .68f, h / 2f, paint)
            }
        }
    }
}
