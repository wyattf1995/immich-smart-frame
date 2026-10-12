package com.wyattfleming.frameos.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrameHomeBackNavigationContractTest {
    @Test
    fun api33RegistersAndUnregistersWhileLegacyActivityBackRemainsAvailable() {
        val navigation = File(
            "src/main/kotlin/com/wyattfleming/frameos/ui/FrameHomeBackNavigation.kt",
        ).readText()
        val activity = File(
            "src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt",
        ).readText()

        assertTrue(navigation.contains("if (Build.VERSION.SDK_INT >= 33) ModernBack.register(activity, onBack) else ({})"))
        assertTrue(navigation.contains("dispatcher.registerOnBackInvokedCallback"))
        assertTrue(navigation.contains("dispatcher.unregisterOnBackInvokedCallback(callback)"))
        assertTrue(activity.contains("override fun onBackPressed()"))
        assertTrue(activity.contains("showMode(FrameMode.HOME, announceHud = false)"))
        assertTrue(activity.contains("unregisterHomeBack = if (touchNavigationEnabled && configuration != null)"))
        assertTrue(activity.contains("FrameHomeBackNavigation.register(this)"))
        assertTrue(activity.contains("unregisterHomeBack?.invoke()"))
        assertTrue(activity.contains("override fun onBackPressed()"))
        assertTrue(activity.contains("touchNavigationEnabled && configuration != null"))
    }
}
