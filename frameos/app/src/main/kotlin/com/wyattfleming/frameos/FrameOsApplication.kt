package com.wyattfleming.frameos

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class FrameOsApplication : Application() {
    val geckoRuntime: GeckoRuntime by lazy {
        GeckoRuntime.create(
            applicationContext,
            GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .remoteDebuggingEnabled(false)
                .aboutConfigEnabled(false)
                .build(),
        )
    }
}
