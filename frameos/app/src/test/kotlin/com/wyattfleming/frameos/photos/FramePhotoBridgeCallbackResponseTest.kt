package com.wyattfleming.frameos.photos

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.gecko.util.GeckoBundle

class FramePhotoBridgeCallbackResponseTest {
    @Test
    fun `acceptance callback uses GeckoBundle and preserves true`() {
        val response = FramePhotoBridge.acceptanceResponse(true)

        assertEquals(GeckoBundle::class.java, response::class.java)
        assertEquals(true, response.getBoolean("accepted"))
    }

    @Test
    fun `acceptance callback uses GeckoBundle and preserves false`() {
        val response = FramePhotoBridge.acceptanceResponse(false)

        assertEquals(GeckoBundle::class.java, response::class.java)
        assertEquals(false, response.getBoolean("accepted"))
    }
}
