package com.wyattfleming.frameos.control
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class FrameCompanionCommandDecoderTest { @Test fun `rejects expired and unsafe commands`() { val command = "{\"id\":\"123e4567-e89b-12d3-a456-426614174000\",\"type\":\"show_mode\",\"mode\":\"home\",\"issuedAt\":1,\"expiresAt\":2}"; assertNull(FrameCompanionCommandDecoder.decode(command, 3)); assertTrue(FrameCompanionCommandDecoder.decode(command, 1) is FrameRemoteCommand.ShowMode); assertNull(FrameCompanionCommandDecoder.decode(command.replace("123e4567-e89b-12d3-a456-426614174000", "x"), 1)) } }
