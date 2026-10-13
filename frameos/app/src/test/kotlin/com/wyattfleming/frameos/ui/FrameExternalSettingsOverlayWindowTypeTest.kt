package com.wyattfleming.frameos.ui

import android.view.WindowManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameExternalSettingsOverlayWindowTypeTest {
    @Test fun `uses legacy phone overlay on Android 6 and 7`() {
        assertEquals(
            FrameExternalSettingsOverlayWindowType.PHONE,
            FrameExternalSettingsOverlayWindowType.forApi(23),
        )
        assertEquals(
            FrameExternalSettingsOverlayWindowType.PHONE,
            FrameExternalSettingsOverlayWindowType.forApi(25),
        )
    }

    @Test fun `uses application overlay from Android 8`() {
        assertEquals(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FrameExternalSettingsOverlayWindowType.forApi(26),
        )
    }

    @Test fun `isolates the Android 8 constant behind a targeted API boundary`() {
        val source = File("src/main/kotlin/com/wyattfleming/frameos/ui/FrameExternalSettingsReturnController.kt").readText()
        assertEquals(1, source.split("@android.annotation.TargetApi(Build.VERSION_CODES.O)").size - 1)
        assertEquals(1, source.split("private fun applicationOverlayForAndroid8OrLater(): Int =").size - 1)
        assertEquals(0, source.split("const val APPLICATION_OVERLAY").size - 1)
    }
}
