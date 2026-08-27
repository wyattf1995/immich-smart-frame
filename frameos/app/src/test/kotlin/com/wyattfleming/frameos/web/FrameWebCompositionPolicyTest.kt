package com.wyattfleming.frameos.web

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebCompositionPolicyTest {
    @Test
    fun `non HA views retain an attached calendar surface for bounded warming`() {
        listOf(FrameMode.PHOTOS, FrameMode.WEATHER, FrameMode.CAMERAS).forEach { mode ->
            val plan = FrameWebCompositionPolicy.forMode(mode)

            assertTrue("$mode should retain the HA surface", plan.retainHomeAssistantSurface)
            assertTrue("$mode should warm Calendar in the background", plan.warmCalendarInBackground)
        }
    }

    @Test
    fun `home and calendar foreground the persistent HA surface`() {
        listOf(FrameMode.HOME, FrameMode.CALENDAR).forEach { mode ->
            val plan = FrameWebCompositionPolicy.forMode(mode)

            assertEquals(FrameWebForeground.HOME_ASSISTANT, plan.foreground)
            assertTrue(plan.retainHomeAssistantSurface)
            assertFalse(plan.warmCalendarInBackground)
        }
    }

    @Test
    fun `photos cameras and native weather keep their intended foreground`() {
        assertEquals(
            FrameWebForeground.PRIMARY,
            FrameWebCompositionPolicy.forMode(FrameMode.PHOTOS).foreground,
        )
        assertEquals(
            FrameWebForeground.PRIMARY,
            FrameWebCompositionPolicy.forMode(FrameMode.CAMERAS).foreground,
        )
        assertEquals(
            FrameWebForeground.NATIVE_WEATHER,
            FrameWebCompositionPolicy.forMode(FrameMode.WEATHER).foreground,
        )
    }
}
