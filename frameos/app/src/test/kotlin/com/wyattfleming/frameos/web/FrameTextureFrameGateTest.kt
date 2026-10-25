package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GeckoView's TextureView backend hands Gecko a new surface after every frame, so a still page redraws as fast as
 * the device allows (91% CPU for a still photo on the 159K, 10/17). Only a frame update that answers Gecko's own
 * request for a new surface may pass.
 */
class FrameTextureFrameGateTest {
    @Test
    fun `frame updates stay out of Gecko while it has a working surface`() {
        val gate = FrameTextureFrameGate()
        repeat(3) { assertFalse(gate.passFrameUpdate()) }
    }

    @Test
    fun `one update answers a request for a new surface and the frames after it stay out`() {
        val gate = FrameTextureFrameGate()
        gate.requestNewSurface()
        gate.requestNewSurface()
        assertTrue(gate.passFrameUpdate())
        assertFalse(gate.passFrameUpdate())
    }

    @Test
    fun `a new texture already answers a pending request`() {
        val gate = FrameTextureFrameGate()
        gate.requestNewSurface()
        gate.surfaceAvailable()
        assertFalse(gate.passFrameUpdate())
    }
}
