package com.wyattfleming.frameos.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameExternalControlPolicyTest {
    @Test
    fun `debug provisioning is one time and release builds reject launch extras`() {
        val debug = FrameExternalControlPolicy(debuggable = true)
        assertTrue(debug.acceptsProvisioning(alreadyConfigured = false))
        assertFalse(debug.acceptsProvisioning(alreadyConfigured = true))
        assertTrue(debug.acceptsCommands())

        val release = FrameExternalControlPolicy(debuggable = false)
        assertFalse(release.acceptsProvisioning(alreadyConfigured = false))
        assertFalse(release.acceptsCommands())
    }
}
