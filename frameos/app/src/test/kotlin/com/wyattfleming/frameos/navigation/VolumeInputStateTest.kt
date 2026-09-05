package com.wyattfleming.frameos.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeInputStateTest {
    @Test
    fun `plus steps forward and minus steps backward once per completed press`() {
        val state = VolumeInputState()

        assertNull(state.onDown(VolumeButton.UP, repeatCount = 0))
        assertEquals(VolumeInputEffect.Step(forward = true), state.onUp(VolumeButton.UP))
        assertNull(state.onDown(VolumeButton.DOWN, repeatCount = 0))
        assertEquals(VolumeInputEffect.Step(forward = false), state.onUp(VolumeButton.DOWN))
    }

    @Test
    fun `held and duplicate down events do not create extra steps`() {
        val state = VolumeInputState()

        assertNull(state.onDown(VolumeButton.UP, repeatCount = 0))
        assertNull(state.onDown(VolumeButton.UP, repeatCount = 0))
        assertNull(state.onDown(VolumeButton.UP, repeatCount = 1))
        assertNull(state.onDown(VolumeButton.UP, repeatCount = 2))
        assertEquals(VolumeInputEffect.Step(forward = true), state.onUp(VolumeButton.UP))
        assertNull(state.onUp(VolumeButton.UP))
    }

    @Test
    fun `either chord order restores auto brightness once and suppresses both steps`() {
        listOf(
            VolumeButton.UP to VolumeButton.DOWN,
            VolumeButton.DOWN to VolumeButton.UP,
        ).forEach { (first, second) ->
            val state = VolumeInputState()

            assertNull(state.onDown(first, repeatCount = 0))
            assertEquals(
                VolumeInputEffect.RestoreAutoBrightness,
                state.onDown(second, repeatCount = 0),
            )
            assertNull(state.onDown(first, repeatCount = 1))
            assertNull(state.onDown(second, repeatCount = 1))
            assertNull(state.onUp(first))
            assertNull(state.onUp(second))
        }
    }

    @Test
    fun `a completed chord clears before the next ordinary press`() {
        val state = VolumeInputState()

        state.onDown(VolumeButton.UP, repeatCount = 0)
        state.onDown(VolumeButton.DOWN, repeatCount = 0)
        state.onUp(VolumeButton.UP)
        state.onUp(VolumeButton.DOWN)

        assertNull(state.onDown(VolumeButton.DOWN, repeatCount = 0))
        assertEquals(VolumeInputEffect.Step(forward = false), state.onUp(VolumeButton.DOWN))
    }

    @Test
    fun `focus reset discards held state and its stale release`() {
        val state = VolumeInputState()

        state.onDown(VolumeButton.UP, repeatCount = 0)
        state.reset()

        assertNull(state.onUp(VolumeButton.UP))
        assertNull(state.onDown(VolumeButton.DOWN, repeatCount = 0))
        assertEquals(VolumeInputEffect.Step(forward = false), state.onUp(VolumeButton.DOWN))
    }

    @Test
    fun `terminal release repeat metadata cannot lose a valid press`() {
        val state = VolumeInputState()

        assertNull(state.onDown(VolumeButton.UP, repeatCount = 0))
        assertEquals(VolumeInputEffect.Step(forward = true), state.onUp(VolumeButton.UP))
    }
}
