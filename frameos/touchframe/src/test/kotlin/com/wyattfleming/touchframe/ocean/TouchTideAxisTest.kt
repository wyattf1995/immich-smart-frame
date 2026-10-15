package com.wyattfleming.touchframe.ocean

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchTideAxisTest {
    @Test
    fun `a label near either edge moves inside the chart and the rest stay centred on their tick`() {
        assertEquals(0f, TouchTideAxis.labelStart(center = 10f, labelWidth = 50f, chartWidth = 1000f), 0f)
        assertEquals(950f, TouchTideAxis.labelStart(center = 990f, labelWidth = 50f, chartWidth = 1000f), 0f)
        assertEquals(475f, TouchTideAxis.labelStart(center = 500f, labelWidth = 50f, chartWidth = 1000f), 0f)
        assertEquals("wider than the chart starts at its left edge", 0f, TouchTideAxis.labelStart(center = 30f, labelWidth = 2000f, chartWidth = 1000f), 0f)
    }
}
