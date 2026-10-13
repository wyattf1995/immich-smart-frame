package com.wyattfleming.frameos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivitySystemSettingsContractTest {
    private val source = File("src/main/kotlin/com/wyattfleming/frameos/MainActivity.kt").readText()

    @Test fun `frame Settings opens the Android Settings landing page`() {
        assertTrue(source.contains("setNeutralButton(\"Android settings\")"))
        assertTrue(source.contains("Intent(android.provider.Settings.ACTION_SETTINGS)"))
        assertFalse(source.contains("Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)"))
    }
}
