package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCompanionOceanCommandTest {
    private fun command(mode: String) =
        "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"show_mode\",\"mode\":\"$mode\",\"issuedAt\":1,\"expiresAt\":2}"

    @Test
    fun `show Ocean opens the Ocean mode, Power stays a Touch-only page and unknown views are refused`() {
        assertEquals(FrameRemoteCommand.ShowMode("123e4567-e89b-12d3-a456-426614174000", FrameMode.OCEAN, 1, 2), FrameCompanionCommandDecoder.decode(command("ocean"), 1))
        assertEquals(FrameRemoteCommand.ShowPage("123e4567-e89b-12d3-a456-426614174000", "power", 1, 2), FrameCompanionCommandDecoder.decode(command("power"), 1))
        assertNull(FrameCompanionCommandDecoder.decode(command("settings"), 1))
    }
}
