package com.wyattfleming.frameos.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameUrlPolicyTest {
    private val policy = FrameUrlPolicy()

    @Test
    fun `accepts local http and remote https display URLs without embedded credentials`() {
        assertTrue(
            policy.isSafeConfigurationUrl(
                "http://frame-host.example.invalid:3000/?curation_profile=family",
            ),
        )
        assertTrue(
            policy.isSafeConfigurationUrl(
                "https://home-assistant.example.invalid/local/frameos.html?view=home",
            ),
        )
    }

    @Test
    fun `rejects credentials unsupported schemes and secret-like query parameters`() {
        val rejected = listOf(
            "https://user:password@home-assistant.example.invalid/",
            "file:///data/local/private.html",
            "javascript:alert(1)",
            "https://home-assistant.example.invalid/?access_token=secret",
            "https://home-assistant.example.invalid/?api_key=secret",
            "https://home-assistant.example.invalid/?password=secret",
        )

        rejected.forEach { url -> assertFalse(url, policy.isSafeConfigurationUrl(url)) }
    }

    @Test
    fun `top level navigation remains on the configured origin`() {
        val configured = "https://home-assistant.example.invalid/local/frameos.html"

        assertTrue(
            policy.isAllowedTopLevelNavigation(
                configuredUrl = configured,
                requestedUrl = "https://home-assistant.example.invalid/wall-panel/home?kiosk",
            ),
        )
        assertFalse(
            policy.isAllowedTopLevelNavigation(
                configuredUrl = configured,
                requestedUrl = "https://login.example.com/phish",
            ),
        )
    }
}
