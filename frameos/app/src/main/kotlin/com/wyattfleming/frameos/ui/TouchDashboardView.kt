package com.wyattfleming.frameos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.wyattfleming.frameos.navigation.FrameMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Native overview with only caller-supplied photo/weather content. It neither
 * fabricates events/forecast values nor recycles the caller-owned bitmap.
 */
class TouchDashboardView(
    context: Context,
    private val onOpenMode: (FrameMode) -> Unit,
    private val onOpenSettings: () -> Unit,
) : FrameLayout(context) {
    private var style = TouchDashboardStyle.forHour(12)
    private var modes = FrameMode.cycleModes(false)
    private val time = text(52f, true)
    private val date = text(16f, false)
    private val weatherSummary = text(23f, true)
    private val weatherDetail = text(14f, false)
    private val photo = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = "Latest cached photo"
    }
    private val photoEmpty = text(16f, false).apply { text = "Your latest photo will appear here" }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var photoBitmap: Bitmap? = null

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setWeather(null, null)
        rebuild()
    }

    fun updateTime(now: ZonedDateTime) {
        time.text = now.format(DateTimeFormatter.ofPattern("h:mm", Locale.getDefault()))
        date.text = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
        val next = TouchDashboardStyle.forHour(now.hour)
        if (next != style) { style = next; rebuild() }
    }

    fun setPhotoPreview(bitmap: Bitmap?) {
        photoBitmap = bitmap
        photo.setImageBitmap(bitmap)
        photo.visibility = if (bitmap == null) View.GONE else View.VISIBLE
        photoEmpty.visibility = if (bitmap == null) View.VISIBLE else View.GONE
    }

    fun setWeather(summary: String?, detail: String?) {
        weatherSummary.text = summary ?: "Weather isn't connected"
        weatherDetail.text = detail ?: "Connect weather to see current conditions"
    }

    fun setModes(modes: List<FrameMode>) {
        this.modes = modes.distinct()
        rebuild()
    }

    private fun rebuild() {
        detachReusableChildren()
        content.removeAllViews()
        setBackgroundColor(style.background)
        content.setPadding(context.dp(28), context.dp(20), context.dp(28), context.dp(24))
        content.addView(header())
        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        columns.addView(primaryColumn(), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.2f).apply {
            marginEnd = context.dp(14)
        })
        columns.addView(actionsColumn(), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        content.addView(columns, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        setPhotoPreview(photoBitmap)
        tintText(content)
    }

    private fun header(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; addView(time); addView(date)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(settingsButton(),
            LinearLayout.LayoutParams(context.dp(124), context.dp(56)))
    }

    private fun primaryColumn(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(photoCard(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.35f).apply {
            bottomMargin = context.dp(14)
        })
        if (modes.contains(FrameMode.WEATHER)) {
            addView(weatherCard(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, .65f))
        }
    }

    private fun photoCard(): View = FrameLayout(context).apply {
        contentDescription = "Open Photos"; isClickable = true; isFocusable = true; minimumTouchTarget(context)
        background = style.card(context.dp(22).toFloat()); setOnClickListener { onOpenMode(FrameMode.PHOTOS) }
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(photoEmpty, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(text(16f, true).apply {
            text = "Photos"; setPadding(context.dp(18), context.dp(14), context.dp(18), context.dp(14))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
    }

    private fun weatherCard(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        contentDescription = "Open Weather"; isClickable = true; isFocusable = true; minimumTouchTarget(context)
        background = style.card(context.dp(22).toFloat()); setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(14))
        setOnClickListener { onOpenMode(FrameMode.WEATHER) }
        addView(text(13f, true).apply { text = "Weather" }); addView(weatherSummary); addView(weatherDetail)
    }

    private fun actionsColumn(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        modes.filter { it != FrameMode.PHOTOS && it != FrameMode.WEATHER }.forEachIndexed { index, mode ->
            val detail = if (mode == FrameMode.CALENDAR) "Open your calendar"
            else "Open " + mode.label.lowercase(Locale.getDefault())
            addView(cardButton(mode.label, mode, { onOpenMode(mode) }, detail),
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    if (index > 0) topMargin = context.dp(12)
                })
        }
    }

    private fun cardButton(label: String, mode: FrameMode?, action: () -> Unit, detail: String? = null): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            contentDescription = detail ?: label; isClickable = true; isFocusable = true; minimumTouchTarget(context)
            background = style.card(context.dp(22).toFloat(), mode == FrameMode.HOME)
            setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12)); setOnClickListener { action() }
            addView(TouchModeIconView(context, mode).apply {
                tint = if (mode == FrameMode.HOME) style.ink else style.accent
            }, LinearLayout.LayoutParams(context.dp(36), context.dp(36)).apply { marginEnd = context.dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(19f, true).apply { text = label })
                detail?.let { addView(text(13f, false).apply { text = it }) }
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun settingsButton(): View = TextView(context).apply {
        text = "Settings"
        gravity = Gravity.CENTER
        contentDescription = "Open Settings"
        isClickable = true
        isFocusable = true
        minimumTouchTarget(context)
        background = style.card(context.dp(22).toFloat())
        setOnClickListener { onOpenSettings() }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans", Typeface.BOLD)
    }

    /**
     * [content.removeAllViews] only detaches the immediate header/column views.
     * These stateful children are nested inside them and must be removed from
     * their old parents before a mode or palette rebuild reattaches them.
     */
    private fun detachReusableChildren() {
        listOf(time, date, weatherSummary, weatherDetail, photo, photoEmpty).forEach { child ->
            (child.parent as? ViewGroup)?.removeView(child)
        }
    }

    private fun text(size: Float, strong: Boolean): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.create("sans", if (strong) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun tintText(view: View) {
        if (view is TextView) {
            view.setTextColor(
                if (view.typeface.style == Typeface.BOLD) style.ink else style.muted,
            )
        }
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) tintText(view.getChildAt(index))
    }
}
