package com.wyattfleming.frameos.control

import com.wyattfleming.frameos.navigation.FrameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameControlCommandCodecTest {
    private val codec = FrameControlCommandCodec()

    @Test
    fun `decodes every shell router command without trusting arbitrary values`() {
        assertEquals(FrameControlCommand.Next, codec.decode(command = "next", mode = null))
        assertEquals(FrameControlCommand.Previous, codec.decode(command = "prev", mode = null))
        assertEquals(FrameControlCommand.Previous, codec.decode(command = "previous", mode = null))
        assertEquals(FrameControlCommand.Home, codec.decode(command = "home", mode = null))
        assertEquals(
            FrameControlCommand.Show(FrameMode.WEATHER),
            codec.decode(command = null, mode = "weather"),
        )

        assertNull(codec.decode(command = "launch-shell", mode = null))
        assertNull(codec.decode(command = null, mode = "settings"))
    }

    @Test
    fun `direct mode takes precedence and stored values round trip`() {
        val command = codec.decode(command = "next", mode = "calendar")
        assertEquals(FrameControlCommand.Show(FrameMode.CALENDAR), command)
        assertEquals(command, codec.decodeStored(codec.encode(requireNotNull(command))))
        assertNull(codec.decodeStored("unknown"))
    }
}
