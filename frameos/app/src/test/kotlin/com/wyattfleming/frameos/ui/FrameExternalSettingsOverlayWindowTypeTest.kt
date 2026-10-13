package com.wyattfleming.frameos.ui

import android.view.WindowManager
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
}
