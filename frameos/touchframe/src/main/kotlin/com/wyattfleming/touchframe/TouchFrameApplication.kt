package com.wyattfleming.touchframe

import android.app.Application
import com.wyattfleming.touchframe.control.TouchHealth
import org.mozilla.geckoview.GeckoRuntime
import java.io.File
import org.mozilla.geckoview.GeckoRuntimeSettings

class TouchFrameApplication : Application() {
    /** Process start time and the starts counted since install, for the health report (control/TouchHealth). */
    var startedAtMillis = 0L
        private set
    var appStarts = 0
        private set

    override fun onCreate() {
        super.onCreate()
        startedAtMillis = System.currentTimeMillis()
        // Gecko's page, GPU and crash-helper processes are this app's processes too and run onCreate as well.
        if (!isMainProcess()) return
        val preferences = getSharedPreferences(TouchHealth.PREFERENCES, MODE_PRIVATE)
        appStarts = preferences.getInt(TouchHealth.APP_STARTS, 0) + 1
        preferences.edit().putInt(TouchHealth.APP_STARTS, appStarts).apply()
    }

    private fun isMainProcess(): Boolean =
        runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000').trim() == packageName }.getOrDefault(true)

    // Native Home is usable before the browser engine is needed for media or sign-in.
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(applicationContext, GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true).remoteDebuggingEnabled(false).aboutConfigEnabled(false).build())
    }
}
