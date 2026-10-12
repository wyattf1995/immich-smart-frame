package com.wyattfleming.frameos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameOAuthNavigationPolicyTest {
    private val policy = FrameOAuthNavigationPolicy(
        "https://home.example.invalid:8444/frameos-touchscreen",
        "https://home.example.invalid:8444/local/frameos-oauth.html",
    )

    @Test
    fun allowsOnlyConfiguredHttpsOriginIncludingAuthorizationQueries() {
        assertEquals(
            FrameOAuthNavigationPolicy.Decision.ALLOW,
            policy.decide("https://home.example.invalid:8444/auth/authorize?response_type=code&state=opaque"),
        )
        assertEquals(
            FrameOAuthNavigationPolicy.Decision.ALLOW,
            policy.decide("https://home.example.invalid:8444/auth/login?next=%2Fauth%2Fauthorize"),
        )
    }

    @Test
    fun rejectsMalformedOrNonHttpsConfiguredOrigins() {
        listOf(
            "http://home.example.invalid:8444",
            "https://user@home.example.invalid:8444",
            "https:///missing-host",
            "not a uri",
        ).forEach { configuredUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                FrameOAuthNavigationPolicy(
                    configuredUrl,
                    "https://home.example.invalid:8444/local/frameos-oauth.html",
                )
            }
        }
    }

    @Test
    fun routesOnlyExactAppCallbackAndRejectsUnsafeNavigation() {
        assertEquals(
            FrameOAuthNavigationPolicy.Decision.CALLBACK,
            policy.decide("frameos://oauth/callback?code=one-time&state=opaque"),
        )
        listOf(
            "frameos://oauth/other?code=one-time&state=opaque",
            "frameos://attacker/callback?code=one-time&state=opaque",
            "frameos://oauth/callback?code=one-time&state=opaque#fragment",
            "http://home.example.invalid:8444/auth/authorize",
            "https://attacker.example.invalid/auth/authorize",
            "https://user@home.example.invalid:8444/auth/authorize",
            "https://home.example.invalid:443/auth/authorize",
        ).forEach { request ->
            assertEquals(FrameOAuthNavigationPolicy.Decision.DENY, policy.decide(request))
        }
    }
}
