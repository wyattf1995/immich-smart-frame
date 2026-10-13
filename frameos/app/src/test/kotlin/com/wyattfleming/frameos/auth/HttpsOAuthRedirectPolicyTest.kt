package com.wyattfleming.frameos.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpsOAuthRedirectPolicyTest {
    private val homeUrl = "https://home.example.invalid:8444/frameos-touchscreen"
    private val callbackPageUrl = "https://home.example.invalid:8444/local/frameos-oauth.html"
    private val policy = FrameOAuthNavigationPolicy(homeUrl, callbackPageUrl)

    @Test
    fun routesExactHttpsCallbackBeforeSameOriginNavigation() {
        assertEquals(
            FrameOAuthNavigationPolicy.Decision.CALLBACK,
            policy.decide("https://home.example.invalid:8444/local/frameos-oauth.html?code=one-time&state=opaque"),
        )
    }

    @Test
    fun treatsEquivalentDefaultHttpsPortAsTheExactCallback() {
        val defaultPortPolicy = FrameOAuthNavigationPolicy(
            "https://home.example.invalid/frameos-touchscreen",
            "https://home.example.invalid/local/frameos-oauth.html",
        )
        assertEquals(
            FrameOAuthNavigationPolicy.Decision.CALLBACK,
            defaultPortPolicy.decide("https://home.example.invalid:443/local/frameos-oauth.html?code=one-time&state=opaque"),
        )
    }

    @Test
    fun deniesMalformedReservedCallbackBeforeSameOriginNavigation() {
        listOf(
            "https://home.example.invalid:8444/local/frameos-oauth.html",
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=one-time&state=opaque#fragment",
            "https://user@home.example.invalid:8444/local/frameos-oauth.html?code=one-time&state=opaque",
            "https://home.example.invalid:8444/local/frameos-oauth.html;evil?code=one-time&state=opaque",
            "https://home.example.invalid:8444/local/first/../frameos-oauth.html?code=one-time&state=opaque",
            "https://home.example.invalid:8444/%6cocal/frameos-oauth.html?code=one-time&state=opaque",
            "https://home.example.invalid:8444/local/%2e%2e/local/frameos-oauth.html?code=one-time&state=opaque",
            "https://home.example.invalid:8444/local/.%2e/local/frameos-oauth.html?code=one-time&state=opaque",
        ).forEach { request ->
            assertEquals(FrameOAuthNavigationPolicy.Decision.DENY, policy.decide(request))
        }
    }

    @Test
    fun rejectsInvalidOrCrossOriginHttpsCallbackTargets() {
        listOf(
            "http://home.example.invalid:8444/local/frameos-oauth.html",
            "https://home.example.invalid:8444/local/frameos-oauth.html?code=preloaded",
            "https://attacker.example.invalid:8444/local/frameos-oauth.html",
        ).forEach { invalidCallback ->
            assertThrows(IllegalArgumentException::class.java) {
                FrameOAuthNavigationPolicy(homeUrl, invalidCallback)
            }
        }
    }
}
