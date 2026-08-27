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
            "https://home-assistant.example.invalid/?ACCESS_TOKEN=secret",
            "https://home-assistant.example.invalid/?%61ccess_token=secret",
            "https://home-assistant.example.invalid/?api_key=secret",
            "https://home-assistant.example.invalid/?password=secret",
            "https://home-assistant.example.invalid/?token=secret",
            "https://home-assistant.example.invalid/#authorization=secret",
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
        assertFalse(
            policy.isAllowedTopLevelNavigation(
                configuredUrl = configured,
                requestedUrl = "https://home-assistant.example.invalid.evil.test/phish",
            ),
        )
    }

    @Test
    fun `default ports normalize but explicit different ports do not`() {
        assertTrue(
            policy.isAllowedTopLevelNavigation(
                configuredUrl = "https://home-assistant.example.invalid/frameos",
                requestedUrl = "https://home-assistant.example.invalid:443/wall-panel/home",
            ),
        )
        assertFalse(
            policy.isAllowedTopLevelNavigation(
                configuredUrl = "https://home-assistant.example.invalid/frameos",
                requestedUrl = "https://home-assistant.example.invalid:8443/wall-panel/home",
            ),
        )
    }

    @Test
    fun `allows only explicitly configured fallback origins`() {
        val origins = listOf(
            "https://home.example.invalid/",
            "https://home-fallback.example.invalid:8443/",
        )

        assertTrue(policy.isAllowedTopLevelNavigation(origins, "https://home-fallback.example.invalid:8443/local/frameos-panel.html"))
        assertFalse(policy.isAllowedTopLevelNavigation(origins, "https://unexpected.example.invalid/local/frameos-panel.html"))
    }
}
