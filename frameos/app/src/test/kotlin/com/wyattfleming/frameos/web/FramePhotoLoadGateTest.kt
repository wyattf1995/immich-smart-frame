package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePhotoLoadGateTest {
    @Test
    fun `only the latest pending URL flushes after its current attachment is ready`() {
        val gate = FramePhotoLoadGate()
        val attachment = gate.beginAttachment()

        gate.defer("https://photos.example/first")
        gate.defer("https://photos.example/latest")

        assertFalse(gate.markReady(attachment + 1))
        assertTrue(gate.markReady(attachment))
        assertEquals("https://photos.example/latest", gate.takePendingIfReady(attachment))
        assertNull(gate.takePendingIfReady(attachment))
    }

    @Test
    fun `stale failed attachment cannot poison a retried current attachment`() {
        val gate = FramePhotoLoadGate()
        val failed = gate.beginAttachment()
        assertTrue(gate.markFailed(failed))

        val retry = gate.beginAttachment()
        gate.defer("https://photos.example/latest")

        assertFalse(gate.markFailed(failed))
        assertTrue(gate.markReady(retry))
        assertEquals("https://photos.example/latest", gate.takePendingIfReady(retry))
    }

    @Test
    fun `late readiness after suspension cannot flush a hidden request`() {
        val gate = FramePhotoLoadGate()
        val attachment = gate.beginAttachment()
        gate.defer("https://photos.example/pending")

        assertTrue(gate.dropPending())
        gate.invalidate()

        assertFalse(gate.markReady(attachment))
        assertNull(gate.takePendingIfReady(attachment))
    }
}

