package com.wyattfleming.frameos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameExternalSettingsReturnControllerTest {
    @Test fun `shows return only when overlay permission exists and settings launches`() {
        val adapter = FakeAdapter(permission = true, settingsLaunch = true)
        val controller = FrameExternalSettingsReturnController(adapter)
        assertEquals(FrameExternalSettingsReturnController.Outcome.LAUNCHED, controller.openSettings())
        assertEquals(1, adapter.shown)
        controller.onResume()
        assertEquals(1, adapter.removed)
    }

    @Test fun `denied overlay permission keeps the user in FrameOS`() {
        val adapter = FakeAdapter(permission = false, settingsLaunch = true)
        assertEquals(FrameExternalSettingsReturnController.Outcome.OVERLAY_PERMISSION_DENIED, FrameExternalSettingsReturnController(adapter).openSettings())
        assertEquals(0, adapter.settingsLaunches)
        assertEquals(0, adapter.shown)
    }

    @Test fun `failed external launch cleans up the return affordance`() {
        val adapter = FakeAdapter(permission = true, settingsLaunch = false)
        assertEquals(FrameExternalSettingsReturnController.Outcome.EXTERNAL_LAUNCH_FAILED, FrameExternalSettingsReturnController(adapter).openSettings())
        assertEquals(1, adapter.shown)
        assertEquals(1, adapter.removed)
    }

    @Test fun `return button launches FrameOS and removes itself once`() {
        val adapter = FakeAdapter(permission = true, settingsLaunch = true)
        val controller = FrameExternalSettingsReturnController(adapter)
        controller.openSettings()
        adapter.returnClick?.invoke()
        assertEquals(1, adapter.frameLaunches)
        assertEquals(1, adapter.removed)
        controller.onDestroy()
        assertEquals(1, adapter.removed)
    }

    private class FakeAdapter(private val permission: Boolean, private val settingsLaunch: Boolean) : FrameExternalSettingsReturnController.Adapter {
        var shown = 0; var removed = 0; var settingsLaunches = 0; var frameLaunches = 0
        var returnClick: (() -> Unit)? = null
        override fun canDrawOverlays() = permission
        override fun showReturn(onReturn: () -> Unit): Boolean { shown++; returnClick = onReturn; return true }
        override fun removeReturn() { removed++ }
        override fun launchAndroidSettings(): Boolean { settingsLaunches++; return settingsLaunch }
        override fun launchFrame(): Boolean { frameLaunches++; return true }
    }
}
