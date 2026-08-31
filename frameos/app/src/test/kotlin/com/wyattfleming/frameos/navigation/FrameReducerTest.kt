package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameReducerTest {
    private val reducer = FrameReducer()

    @Test
    fun `next cycles through all modes and wraps to photos`() {
        var state = FrameState()

        val expected = listOf(
            FrameMode.HOME,
            FrameMode.WEATHER,
            FrameMode.CAMERAS,
            FrameMode.CALENDAR,
            FrameMode.PHOTOS,
        )

        expected.forEach { mode ->
            state = reducer.reduce(state, FrameIntent.NextMode).state
            assertEquals(mode, state.mode)
        }
    }

    @Test
    fun `configured Birds mode follows Weather and wraps through the remaining modes`() {
        var state = FrameState()
        val reducer = FrameReducer { FrameMode.cycleModes(birdsConfigured = true) }

        val expected = listOf(
            FrameMode.HOME,
            FrameMode.WEATHER,
            FrameMode.BIRDS,
            FrameMode.CAMERAS,
            FrameMode.CALENDAR,
            FrameMode.PHOTOS,
        )

        expected.forEach { mode ->
            state = reducer.reduce(state, FrameIntent.NextMode).state
            assertEquals(mode, state.mode)
        }
    }

    @Test
    fun `previous cycles in reverse and wraps to calendar`() {
        val transition = reducer.reduce(FrameState(), FrameIntent.PreviousMode)

        assertEquals(FrameMode.CALENDAR, transition.state.mode)
        assertEquals(FrameEffect.AnnounceMode(FrameMode.CALENDAR), transition.effects.single())
    }

    @Test
    fun `primary action returns every information mode to photos`() {
        listOf(FrameMode.HOME, FrameMode.WEATHER, FrameMode.CAMERAS, FrameMode.CALENDAR).forEach { mode ->
            val transition = reducer.reduce(FrameState(mode = mode), FrameIntent.PrimaryAction)

            assertEquals(FrameMode.PHOTOS, transition.state.mode)
            assertFalse(transition.state.photosPaused)
        }
    }

    @Test
    fun `primary action toggles slideshow pause while already in photos`() {
        val paused = reducer.reduce(FrameState(), FrameIntent.PrimaryAction)
        val resumed = reducer.reduce(paused.state, FrameIntent.PrimaryAction)

        assertTrue(paused.state.photosPaused)
        assertEquals(FrameEffect.AnnounceMessage("Photos paused"), paused.effects.single())
        assertFalse(resumed.state.photosPaused)
        assertEquals(FrameEffect.AnnounceMessage("Photos resumed"), resumed.effects.single())
    }

    @Test
    fun `go home is direct from every mode`() {
        FrameMode.entries.forEach { mode ->
            val transition = reducer.reduce(FrameState(mode = mode), FrameIntent.GoHome)

            assertEquals(FrameMode.HOME, transition.state.mode)
        }
    }

    @Test
    fun `idle expiry always restores an unpaused photo frame`() {
        val state = FrameState(
            mode = FrameMode.CAMERAS,
            photosPaused = true,
            brightnessOverridePercent = 70,
        )

        val transition = reducer.reduce(state, FrameIntent.IdleExpired)

        assertEquals(FrameMode.PHOTOS, transition.state.mode)
        assertFalse(transition.state.photosPaused)
        assertEquals(70, transition.state.brightnessOverridePercent)
    }

    @Test
    fun `brightness changes in ten percent bounded steps and can return to auto`() {
        var state = FrameState()
        repeat(12) { state = reducer.reduce(state, FrameIntent.BrightnessUp).state }
        assertEquals(100, state.brightnessOverridePercent)

        repeat(15) { state = reducer.reduce(state, FrameIntent.BrightnessDown).state }
        assertEquals(10, state.brightnessOverridePercent)

        state = reducer.reduce(state, FrameIntent.RestoreAutoBrightness).state
        assertNull(state.brightnessOverridePercent)
    }
}
