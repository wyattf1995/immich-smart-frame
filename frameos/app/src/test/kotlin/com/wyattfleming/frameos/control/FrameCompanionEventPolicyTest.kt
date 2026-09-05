package com.wyattfleming.frameos.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCompanionEventPolicyTest {
    @Test fun `first event is allowed without timestamp overflow`() {
        assertTrue(FrameCompanionEventPolicy.mayShow("one", emptyList(), null, 1_800_000_000_000))
    }
    @Test fun `duplicates spacing and backward clocks fail closed`() {
        assertFalse(FrameCompanionEventPolicy.mayShow("one", listOf("one"), 1_000, 901_000))
        assertFalse(FrameCompanionEventPolicy.mayShow("two", listOf("one"), 1_000, 900_999))
        assertFalse(FrameCompanionEventPolicy.mayShow("two", listOf("one"), 1_000, 999))
        assertTrue(FrameCompanionEventPolicy.mayShow("two", listOf("one"), 1_000, 901_000))
    }
}
