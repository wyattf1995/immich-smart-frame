package com.wyattfleming.frameos.ui

/** The dashboard is light from 07:00 inclusive through 18:59 inclusive. */
internal object TouchDashboardDaylight {
    fun isDaylight(hour: Int): Boolean = hour in 7..18
}
