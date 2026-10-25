package com.wyattfleming.frameos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivitySystemSettingsContractTest {
    private val activitySource = File("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt").readText()
    private val adapterSource = File("src/main/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsReturnController.kt").readText()

    private val railSource = File("src/main/kotlin/com/wyattfleming/frameos/ui/TouchNavigationRail.kt").readText()

    @Test fun `rail Settings opens Android Settings only from a press and hold`() {
        assertTrue(activitySource.contains("onOpenSettings = { openAndroidSettings() }"))
        assertTrue(activitySource.contains("onSettingsHint = { showHud(getString(R.string.frame_settings_hold_hint)) }"))
        assertTrue(activitySource.contains("private fun openAndroidSettings()"))
        // A tap only explains; Wi-Fi, the date and uninstall sit behind a deliberate hold.
        assertTrue(railSource.contains("item(null, \"Settings\", false, onHold = { onOpenSettings() }) { onSettingsHint() }"))
        assertTrue(railSource.contains("setOnLongClickListener { hold(); true }"))
        assertFalse(railSource.contains("item(null, \"Settings\", false) { onOpenSettings() }"))
        assertTrue(adapterSource.contains("Intent(Settings.ACTION_SETTINGS)"))
        assertFalse(adapterSource.contains("Intent(Settings.ACTION_WIFI_SETTINGS)"))
    }

    @Test fun `idle return schedules from the optional delay so Never schedules nothing`() {
        assertTrue(activitySource.contains("experienceSettings.idleReturnMillis?.let { handler.postDelayed(expireIdle, it) }"))
        assertFalse(activitySource.contains("experienceSettings.idleReturnSeconds * 1_000L"))
    }
}
