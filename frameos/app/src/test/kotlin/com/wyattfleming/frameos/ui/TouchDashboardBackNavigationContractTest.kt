package com.wyattfleming.frameos.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TouchDashboardBackNavigationContractTest {
    @Test
    fun api33RegistersAndUnregistersWhileLegacyActivityBackRemainsAvailable() {
        val navigation = File(
            "src/main/kotlin/com/wyattfleming/frameos/ui/TouchDashboardBackNavigation.kt",
        ).readText()
        val activity = File(
            "src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt",
        ).readText()

        assertTrue(navigation.contains("if (Build.VERSION.SDK_INT >= 33) ModernBack.register(activity, onBack) else ({})"))
        assertTrue(navigation.contains("dispatcher.registerOnBackInvokedCallback"))
        assertTrue(navigation.contains("dispatcher.unregisterOnBackInvokedCallback(callback)"))
        assertTrue(activity.contains("override fun onBackPressed()"))
        assertTrue(activity.contains("showMode(FrameMode.HOME, announceHud = false)"))
        assertTrue("legacy dashboard back helper is isolated from the unified Home activity", !activity.contains("TouchDashboardBackNavigation"))
    }
}
