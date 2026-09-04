package com.wyattfleming.frameos.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRemoteControlPolicyTest {
    @Test fun `only bounded nonexpired commands are accepted`() {
        assertTrue(FrameRemoteControlPolicy.acceptsCommand(issuedAtMillis = 1_000, expiresAtMillis = 60_000, nowMillis = 10_000))
        assertFalse(FrameRemoteControlPolicy.acceptsCommand(issuedAtMillis = 1_000, expiresAtMillis = 61_001, nowMillis = 10_000))
        assertFalse(FrameRemoteControlPolicy.acceptsCommand(issuedAtMillis = 1_000, expiresAtMillis = 9_999, nowMillis = 10_000))
        assertFalse(FrameRemoteControlPolicy.acceptsProfile("family;rm"))
        assertTrue(FrameRemoteControlPolicy.acceptsProfile("family_2026"))
    }
}
