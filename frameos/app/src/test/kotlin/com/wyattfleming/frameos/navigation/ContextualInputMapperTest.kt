package com.wyattfleming.frameos.navigation

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextualInputMapperTest {
    private val mapper = ContextualInputMapper()

    @Test
    fun `tab and shift tab step photos and weather in opposite directions`() {
        assertEquals(
            ContextualFrameAction.PhotoStep(forward = true),
            mapper.map(FrameMode.PHOTOS, KeyEvent.KEYCODE_TAB, shiftPressed = false),
        )
        assertEquals(
            ContextualFrameAction.PhotoStep(forward = false),
            mapper.map(FrameMode.PHOTOS, KeyEvent.KEYCODE_TAB, shiftPressed = true),
        )
        assertEquals(
            ContextualFrameAction.WeatherPageStep(forward = true),
            mapper.map(FrameMode.WEATHER, KeyEvent.KEYCODE_TAB, shiftPressed = false),
        )
        assertEquals(
            ContextualFrameAction.WeatherPageStep(forward = false),
            mapper.map(FrameMode.WEATHER, KeyEvent.KEYCODE_TAB, shiftPressed = true),
        )
    }

    @Test
    fun `HA surfaces retain browser focus controls while raw star remains trackable`() {
        listOf(FrameMode.HOME, FrameMode.CAMERAS, FrameMode.CALENDAR).forEach { mode ->
            assertEquals(
                ContextualFrameAction.ForwardToWeb(KeyEvent.KEYCODE_TAB),
                mapper.map(mode, KeyEvent.KEYCODE_TAB, shiftPressed = false),
            )
            assertNull(mapper.map(mode, KeyEvent.KEYCODE_STAR, shiftPressed = false))
            assertEquals(
                ContextualFrameAction.ForwardToWeb(KeyEvent.KEYCODE_ENTER),
                mapper.mapStarRelease(mode),
            )
        }
    }

    @Test
    fun `short raw star keeps native primary behavior outside HA`() {
        listOf(FrameMode.PHOTOS, FrameMode.WEATHER).forEach { mode ->
            assertEquals(ContextualFrameAction.PrimaryAction, mapper.mapStarRelease(mode))
        }
    }

    @Test
    fun `native enter remains the contextual primary action`() {
        listOf(FrameMode.PHOTOS, FrameMode.WEATHER).forEach { mode ->
            assertEquals(
                ContextualFrameAction.PrimaryAction,
                mapper.map(mode, KeyEvent.KEYCODE_ENTER, shiftPressed = false),
            )
            assertEquals(
                ContextualFrameAction.PrimaryAction,
                mapper.map(mode, KeyEvent.KEYCODE_DPAD_CENTER, shiftPressed = false),
            )
        }
        assertNull(mapper.map(FrameMode.PHOTOS, KeyEvent.KEYCODE_A, shiftPressed = false))
    }
}
