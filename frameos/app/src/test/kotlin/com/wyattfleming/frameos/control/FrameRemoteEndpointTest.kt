package com.wyattfleming.frameos.control

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRemoteEndpointTest {
    @Test fun `normalized endpoints have value equality across credential reads`() {
        assertEquals(FrameRemoteEndpoint.from("https://frame.example"), FrameRemoteEndpoint.from("https://frame.example/other"))
    }
}
