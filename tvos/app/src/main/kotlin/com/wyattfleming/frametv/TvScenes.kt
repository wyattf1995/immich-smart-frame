package com.wyattfleming.frametv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import com.wyattfleming.frameos.ui.NativeSceneDensity

/**
 * The context FrameOS's shared Weather and Ocean screens are built in. They are laid out for a panel at least 800 dp
 * tall; the Sony's UI reports 1920×1080 at 320 dpi, only 540 dp, so they render at the lower density that
 * gives them that height, exactly as on the Lenovo frame. Frame TV's own home screen keeps the TV's density.
 */
object TvScenes {
    fun context(activity: Activity): Context {
        val configuration = activity.resources.configuration
        val heightPx = configuration.screenHeightDp * configuration.densityDpi / DisplayMetrics.DENSITY_DEFAULT
        val dpi = NativeSceneDensity.dpiFor(heightPx, configuration.screenHeightDp) ?: return activity
        val scaled = Configuration(configuration).apply {
            screenWidthDp = configuration.screenWidthDp * configuration.densityDpi / dpi
            screenHeightDp = configuration.screenHeightDp * configuration.densityDpi / dpi
            smallestScreenWidthDp = configuration.smallestScreenWidthDp * configuration.densityDpi / dpi
            densityDpi = dpi
        }
        return ContextThemeWrapper(activity, R.style.AppTheme).apply { applyOverrideConfiguration(scaled) }
    }
}
