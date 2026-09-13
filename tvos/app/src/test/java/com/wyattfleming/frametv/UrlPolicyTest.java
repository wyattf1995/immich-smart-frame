package com.wyattfleming.frametv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UrlPolicyTest {
    private static final String LOCAL = "http://192.168.86.20:8123/dashboard";
    private static final String HTTPS = "https://dashboard.example.com/home";

    @Test
    public void validateAcceptsHttpsAndPrivateLanHttp() {
        assertEquals(HTTPS, UrlPolicy.validate(HTTPS));
        assertEquals(LOCAL, UrlPolicy.validate(LOCAL));
        assertEquals("http://home-assistant.local:8123/", UrlPolicy.validate(
                "http://home-assistant.local:8123/"));
    }

    @Test
    public void validateRejectsNonWebSchemesAndPublicCleartext() {
        assertInvalid("javascript:alert(1)");
        assertInvalid("file:///sdcard/dashboard.html");
        assertInvalid("content://com.example.provider/dashboard");
        assertInvalid("intent://dashboard/#Intent;scheme=https;end");
        assertInvalid("http://example.com/dashboard");
    }

    @Test
    public void validateRejectsCredentialsAndAmbiguousHosts() {
        assertInvalid("https://owner:secret@dashboard.example.com/");
        assertInvalid("https://dashboard.example.com@evil.example/");
        assertInvalid("https://owner@dashboard.example.com/");
    }

    @Test
    public void validateRejectsCredentialsInQueryOrFragmentIncludingEncodedKeys() {
        assertInvalid("https://dashboard.example.com/?token=abc");
        assertInvalid("https://dashboard.example.com/?pass_word=abc");
        assertInvalid("https://dashboard.example.com/?api%5Fkey=abc");
        assertInvalid("https://dashboard.example.com/?x-api-key=abc");
        assertInvalid("https://dashboard.example.com/?api_key_id=abc");
        assertInvalid("https://dashboard.example.com/?key=abc");
        assertInvalid("https://dashboard.example.com/#access_token=abc");
        assertInvalid("https://dashboard.example.com/#%70%61%73%73%77%6f%72%64=abc");
        assertInvalid("https://dashboard.example.com/#x-api-key=abc");
        assertInvalid("https://dashboard.example.com/#api_key_id=abc");
        assertInvalid("https://dashboard.example.com/#key=abc");
    }

    @Test
    public void validateRejectsMalformedAndUnsafePorts() {
        assertInvalid("https://dashboard.example.com:not-a-port/");
        assertInvalid("https://dashboard.example.com:0/");
        assertInvalid("https://dashboard.example.com:65536/");
    }

    @Test
    public void navigationAllowsOnlySameOriginWithEffectivePorts() {
        assertTrue(UrlPolicy.navigationAllowed(HTTPS, "https://dashboard.example.com/other"));
        assertTrue(UrlPolicy.navigationAllowed("https://dashboard.example.com:443/start",
                "https://dashboard.example.com/other"));
        assertTrue(UrlPolicy.navigationAllowed(LOCAL, "http://192.168.86.20:8123/music"));
    }

    @Test
    public void navigationRejectsCrossOriginSchemeAndLookalikeHosts() {
        assertFalse(UrlPolicy.navigationAllowed(HTTPS, "https://evil.example.com/"));
        assertFalse(UrlPolicy.navigationAllowed(HTTPS, "http://dashboard.example.com/"));
        assertFalse(UrlPolicy.navigationAllowed(HTTPS, "https://dashboard.example.com:444/"));
        assertFalse(UrlPolicy.navigationAllowed(HTTPS, "https://dashboard.example.com.evil.example/"));
        assertFalse(UrlPolicy.navigationAllowed(HTTPS, "https://dashboard.example.com@evil.example/"));
    }

    private static void assertInvalid(String value) {
        assertThrows(IllegalArgumentException.class, () -> UrlPolicy.validate(value));
    }
}
