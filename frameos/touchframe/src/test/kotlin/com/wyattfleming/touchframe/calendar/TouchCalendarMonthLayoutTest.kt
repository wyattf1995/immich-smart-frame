package com.wyattfleming.touchframe.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCalendarMonthLayoutTest {
    @Test
    fun `six-week months shrink their cells to fit the frame`() {
        // 1920×1080 under an 80dp bar: 1000dp of calendar; 1280×800 under 68dp: 732dp.
        val wallFive = TouchCalendarMonthLayout.cellHeightDp(1000f, 5, wall = true)
        val wallSix = TouchCalendarMonthLayout.cellHeightDp(1000f, 6, wall = true)
        val panelSix = TouchCalendarMonthLayout.cellHeightDp(732f, 6, wall = false)
        assertEquals(140, wallFive)
        assertTrue(wallSix < wallFive)
        val gap = TouchCalendarMonthLayout.ROW_GAP_DP
        assertTrue(6 * (wallSix + gap) + TouchCalendarMonthLayout.WALL_CHROME_DP <= 1000f)
        assertTrue(6 * (panelSix + gap) + TouchCalendarMonthLayout.PANEL_CHROME_DP <= 732f)
        // Five-week months on the panel keep the reviewed full-size cells within a few dp.
        assertTrue(TouchCalendarMonthLayout.cellHeightDp(732f, 5, wall = false) >= 96)
    }

    @Test
    fun `cells never shrink below a readable size and use the full size before layout`() {
        assertEquals(72, TouchCalendarMonthLayout.cellHeightDp(400f, 6, wall = false))
        assertEquals(100, TouchCalendarMonthLayout.cellHeightDp(0f, 6, wall = false))
    }
}
