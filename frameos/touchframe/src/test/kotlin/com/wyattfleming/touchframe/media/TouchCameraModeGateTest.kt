package com.wyattfleming.touchframe.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchCameraModeGateTest {
    @Test fun selectionAndLiveFailureNeverStartSnapshotWork() {
        val gate = TouchCameraModeGate()
        assertFalse(gate.snapshotMayStart())
        gate.requestSnapshot()
        assertTrue(gate.snapshotMayStart())
        gate.selectOrPrepareLive()
        assertFalse(gate.snapshotMayStart())
        gate.requestSnapshot()
        gate.selectOrPrepareLive() // Same-camera selection is also a fresh live-ready state.
        assertFalse(gate.snapshotMayStart())
    }

    @Test fun onlyExplicitSnapshotActionOpensTheSnapshotGate() {
        val gate = TouchCameraModeGate()
        gate.selectOrPrepareLive()
        assertFalse(gate.snapshotMayStart())
        gate.requestSnapshot()
        assertTrue(gate.snapshotMayStart())
    }
}
