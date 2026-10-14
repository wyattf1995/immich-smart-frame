package com.wyattfleming.touchframe.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCameraLiveStatusGateTest {
    @Test fun onlyCurrentFailedLiveSurfaceRestoresNativeControls() {
        val gate = TouchCameraLiveStatusGate()
        val first = gate.activate()
        assertFalse(gate.shouldRestore(first, TouchLiveCameraBridge.Status.READY))
        assertTrue(gate.shouldRestore(first, TouchLiveCameraBridge.Status.FAILED))
        val second = gate.activate()
        assertFalse(gate.shouldRestore(first, TouchLiveCameraBridge.Status.FAILED))
        assertTrue(gate.shouldRestore(second, TouchLiveCameraBridge.Status.FAILED))
        gate.release()
        assertFalse(gate.shouldRestore(second, TouchLiveCameraBridge.Status.FAILED))
    }

    @Test fun activeSurfaceStaysEligibleUntilRelease() {
        val gate = TouchCameraLiveStatusGate()
        val generation = gate.activate()
        assertTrue(gate.isActive(generation))
        gate.release()
        assertFalse(gate.isActive(generation))
    }
}
