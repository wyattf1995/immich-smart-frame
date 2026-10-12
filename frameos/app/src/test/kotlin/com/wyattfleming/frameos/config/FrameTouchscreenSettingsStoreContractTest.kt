package com.wyattfleming.frameos.config

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTouchscreenSettingsStoreContractTest {
    @Test
    fun `missing touchscreen startup preference falls through to Photos`() {
        val source = File("src/main/kotlin/com/wyattfleming/frameos/config/FrameTouchscreenSettingsStore.kt").readText()

        assertTrue(source.contains("preferences.getBoolean(\"start_with_photos\", true)"))
    }
}
