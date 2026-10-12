package com.wyattfleming.frameos.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

internal data class TouchDashboardStyle(
    val background: Int, val surface: Int, val ink: Int, val muted: Int,
    val accent: Int, val selectedInk: Int, val gold: Int, val outline: Int,
) {
    fun card(radius: Float, selected: Boolean = false) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(if (selected) accent else surface)
        setStroke(1, if (selected) accent else outline)
        cornerRadius = radius
    }

    companion object {
        fun forHour(hour: Int) = if (TouchDashboardDaylight.isDaylight(hour)) {
            TouchDashboardStyle(
                Color.rgb(244, 240, 230), Color.rgb(255, 253, 247), Color.rgb(20, 39, 65),
                Color.rgb(88, 103, 116), Color.rgb(31, 112, 116), Color.WHITE, Color.rgb(182, 132, 49),
                Color.rgb(216, 211, 199),
            )
        } else {
            TouchDashboardStyle(
                Color.rgb(13, 29, 49), Color.rgb(23, 45, 69), Color.rgb(247, 244, 235),
                Color.rgb(184, 197, 204), Color.rgb(77, 171, 168), Color.rgb(13, 29, 49), Color.rgb(223, 180, 94),
                Color.rgb(54, 79, 99),
            )
        }
    }
}

internal fun View.minimumTouchTarget(context: android.content.Context) {
    minimumHeight = context.dp(56)
    minimumWidth = context.dp(56)
}
