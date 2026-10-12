package com.wyattfleming.frameos.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wyattfleming.frameos.navigation.FrameMode
import java.time.ZonedDateTime

class TouchNavigationRail private constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int,
    private val onOpenSettings: () -> Unit,
    private val onOpenMode: (FrameMode) -> Unit,
) : LinearLayout(context, attrs, defStyleAttr) {
    constructor(context: Context) : this(context, null, 0, {}, {})

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0, {}, {})

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, {}, {})

    constructor(
        context: Context,
        onOpenSettings: () -> Unit,
        onOpenMode: (FrameMode) -> Unit,
    ) : this(context, null, 0, onOpenSettings, onOpenMode)

    private var style = TouchDashboardStyle.forHour(12)
    private var selected: FrameMode? = null
    private var modes: List<FrameMode> = FrameMode.cycleModes(false)
    private val items = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private val scroller = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        addView(items, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        minimumWidth = context.dp(88)
        layoutParams = LayoutParams(context.dp(88), LayoutParams.MATCH_PARENT)
        rebuild()
    }
    fun setModes(modes: List<FrameMode>) {
        val next = modes.distinct()
        if (this.modes == next) return
        this.modes = next
        rebuild()
    }

    fun setSelection(mode: FrameMode?) {
        if (selected == mode) return
        selected = mode
        rebuild()
    }
    fun updateTime(now: ZonedDateTime) {
        val next = TouchDashboardStyle.forHour(now.hour)
        if (next != style) { style = next; rebuild() }
    }
    private fun rebuild() {
        removeAllViews(); items.removeAllViews(); setBackgroundColor(style.background)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        modes.forEach { mode -> items.addView(item(mode, mode.label, selected == mode) { onOpenMode(mode) }) }
        items.addView(item(null, "Settings", false) { onOpenSettings() })
    }
    private fun item(mode: FrameMode?, label: String, active: Boolean, action: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER; minimumTouchTarget(context)
            contentDescription = label; isFocusable = true; isClickable = true
            background = style.card(context.dp(16).toFloat(), active)
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            setOnClickListener { action() }
            addView(TouchModeIconView(context, mode).apply { tint = if (active) style.selectedInk else style.accent },
                LayoutParams(context.dp(28), context.dp(28)))
            addView(TextView(context).apply {
                text = label; setTextColor(if (active) style.selectedInk else style.muted)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans", Typeface.BOLD); gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, context.dp(68)).apply {
                setMargins(context.dp(6), context.dp(6), context.dp(6), 0)
            }
        }
}
