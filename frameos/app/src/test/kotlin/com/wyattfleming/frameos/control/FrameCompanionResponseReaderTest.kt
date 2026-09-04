package com.wyattfleming.frameos.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class FrameCompanionResponseReaderTest {
    @Test
    fun `response reader accepts exactly sixty four kibibytes`() {
        val body = "x".repeat(FrameCompanionResponseReader.MAX_RESPONSE_BYTES)
        assertEquals(body, FrameCompanionResponseReader.read(ByteArrayInputStream(body.toByteArray())))
    }

    @Test
    fun `response reader rejects an oversized body without allocating it as text`() {
        val body = ByteArray(FrameCompanionResponseReader.MAX_RESPONSE_BYTES + 1)
        assertNull(FrameCompanionResponseReader.read(ByteArrayInputStream(body)))
    }
}
