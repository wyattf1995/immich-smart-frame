package com.wyattfleming.frameos.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWebPhotosReadinessStateTest {
    @Test
    fun `outer paint cannot qualify Photos until the current bridge generation is observed`() {
        val readiness = FrameWebPhotosReadinessState()
        readiness.begin(8L)

        assertFalse(readiness.isReady())
        assertFalse(readiness.recordObservation(7L))
        assertFalse(readiness.isReady())
        assertTrue(readiness.recordObservation(8L))
        assertTrue(readiness.isReady())
    }

    @Test
    fun `a new request rejects its old observation and requires a new one`() {
        val readiness = FrameWebPhotosReadinessState()
        readiness.begin(8L)
        assertTrue(readiness.recordObservation(8L))
        assertTrue(readiness.isReady())

        readiness.begin(9L)
        assertFalse(readiness.isReady())
        assertFalse(readiness.recordObservation(8L))
        assertFalse(readiness.isReady())
        assertTrue(readiness.recordObservation(9L))
        assertTrue(readiness.isReady())
    }

    @Test
    fun `missing bridge generation fails closed`() {
        val readiness = FrameWebPhotosReadinessState()
        readiness.begin(null)

        assertFalse(readiness.recordObservation(1L))
        assertFalse(readiness.isReady())
    }
}
