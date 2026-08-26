package com.wyattfleming.frameos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthSessionCodecTest {
    private val codec = OAuthSessionCodec()

    @Test
    fun `round trips the renewable session inside the encrypted store envelope`() {
        val session = OAuthSession("access-secret", "refresh-secret", expiresAtEpochMillis = 123_456L, authEpoch = "account-a")

        val decoded = codec.decode(codec.encode(session))

        assertEquals("access-secret", decoded?.accessToken)
        assertEquals("refresh-secret", decoded?.refreshToken)
        assertEquals(123_456L, decoded?.expiresAtEpochMillis)
        assertEquals("account-a", decoded?.authEpoch)
        assertFalse(decoded.toString().contains("access-secret"))
        assertFalse(decoded.toString().contains("refresh-secret"))
    }

    @Test
    fun `migrates sessions without an epoch into a legacy scope`() {
        val decoded = codec.decode("""{"version":1,"access_token":"a","refresh_token":"r","expires_at":1}""")

        assertEquals("legacy", decoded?.authEpoch)
    }

    @Test
    fun `rejects corrupt empty and unsupported session payloads`() {
        assertNull(codec.decode("not-json"))
        assertNull(codec.decode("""{"version":2,"access_token":"a","refresh_token":"r","expires_at":1}"""))
        assertNull(codec.decode("""{"version":1,"access_token":"","refresh_token":"r","expires_at":1}"""))
        assertNull(codec.decode("""{"version":1,"access_token":"a","refresh_token":"r","expires_at":0}"""))
    }
}
