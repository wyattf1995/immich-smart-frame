package com.wyattfleming.frameos.navigation

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalInputMapperTest {
    private val mapper = PhysicalInputMapper()

    @Test
    fun `verified horizontal gesture scan codes navigate the mode cycle`() {
        assertEquals(FrameIntent.NextMode, mapper.mapKeyDown(keyCode = 0, scanCode = 251))
        assertEquals(FrameIntent.PreviousMode, mapper.mapKeyDown(keyCode = 0, scanCode = 252))
    }

    @Test
    fun `local dpad bridge navigates when firmware reserves gesture key codes`() {
        assertEquals(
            FrameIntent.NextMode,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, scanCode = 0),
        )
        assertEquals(
            FrameIntent.PreviousMode,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_DPAD_LEFT, scanCode = 0),
        )
    }

    @Test
    fun `lenovo vendor gesture key codes navigate without a scan code bridge`() {
        assertEquals(FrameIntent.NextMode, mapper.mapKeyDown(keyCode = 291, scanCode = 0))
        assertEquals(FrameIntent.PreviousMode, mapper.mapKeyDown(keyCode = 292, scanCode = 0))
    }

    @Test
    fun `star is contextual and long star goes directly home`() {
        assertEquals(
            FrameIntent.PrimaryAction,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_STAR, scanCode = 255),
        )
        assertEquals(
            FrameIntent.GoHome,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_STAR, scanCode = 255, isLongPress = true),
        )
    }

    @Test
    fun `volume buttons provide direct mode navigation without a shell bridge`() {
        assertEquals(
            FrameIntent.NextMode,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_VOLUME_UP, scanCode = 115),
        )
        assertEquals(
            FrameIntent.PreviousMode,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_VOLUME_DOWN, scanCode = 114),
        )
    }

    @Test
    fun `unverified gesture scan codes remain unbound in the MVP`() {
        listOf(249, 250, 253, 254).forEach { scanCode ->
            assertNull(mapper.mapKeyDown(keyCode = 0, scanCode = scanCode))
        }
    }

    @Test
    fun `ordinary keyboard input is left for the active page`() {
        assertNull(mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_TAB, scanCode = 15))
    }

    @Test
    fun `router home injection has a dedicated non system key`() {
        assertEquals(
            FrameIntent.GoHome,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_MOVE_HOME, scanCode = 102),
        )
    }

    @Test
    fun `remapped enter keys trigger the contextual primary action`() {
        assertEquals(
            FrameIntent.PrimaryAction,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_ENTER, scanCode = 28),
        )
        assertEquals(
            FrameIntent.PrimaryAction,
            mapper.mapKeyDown(keyCode = KeyEvent.KEYCODE_DPAD_CENTER, scanCode = 232),
        )
    }
}
