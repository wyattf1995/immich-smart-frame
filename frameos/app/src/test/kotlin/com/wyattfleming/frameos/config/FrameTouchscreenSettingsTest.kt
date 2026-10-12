package com.wyattfleming.frameos.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTouchscreenSettingsTest {
    @Test
    fun `defaults keep dashboard and hidden Home detachment opted out but start Photos`() {
        val settings = FrameTouchscreenSettings()

        assertFalse(settings.dashboardEnabled)
        assertFalse(settings.detachHiddenHome)
        assertTrue(settings.startWithPhotos)
        assertFalse(settings.showsDashboard(hasTouchscreen = true))
    }

    @Test
    fun `an explicit Home startup preference remains available`() {
        assertFalse(FrameTouchscreenSettings(startWithPhotos = false).startWithPhotos)
    }

    @Test
    fun `dashboard requires both its opt in and a physical touchscreen`() {
        assertFalse(FrameTouchscreenSettings(dashboardEnabled = true).showsDashboard(hasTouchscreen = false))
        assertTrue(FrameTouchscreenSettings(dashboardEnabled = true).showsDashboard(hasTouchscreen = true))
    }

    @Test
    fun `hidden Home detachment is independent from dashboard visibility`() {
        val detachOnly = FrameTouchscreenSettings(detachHiddenHome = true)
        val dashboardOnly = FrameTouchscreenSettings(dashboardEnabled = true)

        assertFalse(detachOnly.showsDashboard(hasTouchscreen = true))
        assertTrue(detachOnly.detachHiddenHome)
        assertTrue(dashboardOnly.showsDashboard(hasTouchscreen = true))
        assertFalse(dashboardOnly.detachHiddenHome)
    }
}
