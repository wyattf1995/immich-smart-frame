package com.wyattfleming.frameos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivitySystemSettingsContractTest {
    private val activitySource = File("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt").readText()
    private val adapterSource = File("src/main/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsReturnController.kt").readText()

    @Test fun `rail Settings opens Android Settings directly through the return controller`() {
        assertTrue(activitySource.contains("onOpenSettings = { openAndroidSettings() }"))
        assertTrue(activitySource.contains("private fun openAndroidSettings()"))
        assertTrue(adapterSource.contains("Intent(Settings.ACTION_SETTINGS)"))
        assertFalse(adapterSource.contains("Intent(Settings.ACTION_WIFI_SETTINGS)"))
    }
}
