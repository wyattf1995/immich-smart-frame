package com.wyattfleming.touchframe.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCalendarPresentationTest {
    @Test
    fun `calendar controls retain readable text and unclipped weekday space`() {
        assertTrue(TouchCalendarPresentation.CONTROL_TEXT_SP in 20f..22f)
        assertTrue(TouchCalendarPresentation.DAY_LABEL_TEXT_SP >= 20f)
        assertTrue(TouchCalendarPresentation.WEEKDAY_HEADER_TEXT_SP >= 20f)
        assertTrue(TouchCalendarPresentation.WEEKDAY_HEADER_MIN_HEIGHT_DP >= 48)
    }

    @Test
    fun `calendar period controls have explicit accessible descriptions`() {
        assertEquals("Previous calendar period", TouchCalendarPresentation.contentDescriptionFor("Previous"))
        assertEquals("Next calendar period", TouchCalendarPresentation.contentDescriptionFor("Next"))
        assertEquals("Today", TouchCalendarPresentation.contentDescriptionFor("Today"))
    }
}
