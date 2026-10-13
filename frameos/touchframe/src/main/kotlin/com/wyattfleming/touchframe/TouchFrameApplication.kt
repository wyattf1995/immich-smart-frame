package com.wyattfleming.touchframe

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class TouchFrameApplication : Application() {
    // Native Home is usable before the browser engine is needed for media or sign-in.
    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(applicationContext, GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true).remoteDebuggingEnabled(false).aboutConfigEnabled(false).build())
    }
}
