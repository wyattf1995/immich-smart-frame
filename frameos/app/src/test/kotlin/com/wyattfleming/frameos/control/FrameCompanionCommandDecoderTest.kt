package com.wyattfleming.frameos.control
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class FrameCompanionCommandDecoderTest { @Test fun `rejects expired and unsafe commands`() { assertNull(FrameCompanionCommandDecoder.decode("{\"id\":\"x\",\"type\":\"show_mode\",\"mode\":\"home\",\"issuedAt\":1,\"expiresAt\":2}", 3)); assertTrue(FrameCompanionCommandDecoder.decode("{\"id\":\"x\",\"type\":\"show_mode\",\"mode\":\"home\",\"issuedAt\":1,\"expiresAt\":2}", 1) is FrameRemoteCommand.ShowMode) } }
