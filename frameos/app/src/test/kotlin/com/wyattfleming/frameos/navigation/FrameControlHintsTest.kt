package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameControlHintsTest {
    @Test fun `photo hint reflects pause state and web hint describes focus controls`() {
        assertTrue(FrameControlHints.forMode(FrameMode.PHOTOS).contains("pauses"))
        assertTrue(FrameControlHints.forMode(FrameMode.PHOTOS, photosPaused = true).contains("resumes"))
        assertTrue(FrameControlHints.forMode(FrameMode.CALENDAR).contains("focus"))
    }

    @Test fun `Weather and Ocean hints no longer promise volume paging`() {
        listOf(FrameMode.WEATHER, FrameMode.OCEAN).forEach { mode ->
            assertEquals("★ returns to photos", FrameControlHints.forMode(mode))
        }
    }
}
