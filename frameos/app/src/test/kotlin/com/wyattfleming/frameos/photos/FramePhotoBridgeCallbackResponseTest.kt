package com.wyattfleming.frameos.photos

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.gecko.util.GeckoBundle

class FramePhotoBridgeCallbackResponseTest {
    @Test
    fun `acceptance callback uses GeckoBundle and preserves true`() {
        val response: Any = FramePhotoBridge.acceptanceResponse(true)

        assertEquals(GeckoBundle::class.java, response::class.java)
        assertEquals(true, (response as GeckoBundle).getBoolean("accepted"))
    }

    @Test
    fun `acceptance callback uses GeckoBundle and preserves false`() {
        val response: Any = FramePhotoBridge.acceptanceResponse(false)

        assertEquals(GeckoBundle::class.java, response::class.java)
        assertEquals(false, (response as GeckoBundle).getBoolean("accepted"))
    }
}
