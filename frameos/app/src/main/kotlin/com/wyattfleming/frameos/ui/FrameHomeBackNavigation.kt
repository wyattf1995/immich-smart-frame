package com.wyattfleming.frameos.ui

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi

/** Keep API 33 types out of the legacy class-loading path. */
internal object TouchDashboardBackNavigation {
    fun register(activity: Activity, onBack: () -> Unit): () -> Unit =
        if (Build.VERSION.SDK_INT >= 33) ModernBack.register(activity, onBack) else ({})

    @RequiresApi(33)
    private object ModernBack {
        fun register(activity: Activity, onBack: () -> Unit): () -> Unit {
            val callback = android.window.OnBackInvokedCallback { onBack() }
            val dispatcher = activity.onBackInvokedDispatcher
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            return { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }
}
