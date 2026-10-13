package com.wyattfleming.touchframe.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSettingsReturnPlanTest {
    @Test
    fun `return prefers the owning task before a single top launch fallback`() {
        assertEquals(TouchSettingsReturnRoute.MOVE_EXISTING_TASK, TouchSettingsReturnPlan.routeFor(hasAppTask = true))
        assertEquals(TouchSettingsReturnRoute.LAUNCH_SINGLE_TOP_TASK, TouchSettingsReturnPlan.routeFor(hasAppTask = false))
        assertTrue(TouchSettingsReturnPlan.FALLBACK_LAUNCH_FLAGS and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(TouchSettingsReturnPlan.FALLBACK_LAUNCH_FLAGS and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertEquals(0, TouchSettingsReturnPlan.FALLBACK_LAUNCH_FLAGS and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    @Test
    fun `task recovery selects only the hosting activity task`() {
        assertTrue(TouchSettingsReturnPlan.isOwningTask(candidateTaskId = 41, activityTaskId = 41))
        assertFalse(TouchSettingsReturnPlan.isOwningTask(candidateTaskId = 42, activityTaskId = 41))
    }

    @Test
    fun `return dispatch retains overlay until the host resumes`() {
        assertFalse(TouchSettingsReturnPlan.dismissOverlayAfterReturnDispatch())
    }
}
