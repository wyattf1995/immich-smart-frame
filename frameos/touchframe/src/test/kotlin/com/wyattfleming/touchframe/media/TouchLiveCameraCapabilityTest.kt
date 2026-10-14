package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchLiveCameraCapabilityTest {
    private val firstNonce = "first-capability-nonce-1234"
    private val secondNonce = "second-capability-nonce-123"
    private val policy = TouchLiveCameraPolicy(
        "https://home.example:8443",
        listOf("camera.entryway", "camera.backyard"),
    )

    @Test fun websocketEndpointIsDerivedOnlyFromTheConfiguredOrigin() {
        assertEquals("wss://home.example:8443/api/websocket", policy.websocketUrl.toString())
        assertTrue(policy.allowsCamera("camera.entryway"))
        assertFalse(policy.allowsCamera("camera.other"))
    }

    @Test fun packagedPageMustBeTheExactTopLevelExtensionDocument() {
        assertTrue(policy.isTrustedPageUrl("moz-extension://generated-id/live.html"))
        assertFalse(policy.isTrustedPageUrl("moz-extension://generated-id/live.html?x=1"))
        assertFalse(policy.isTrustedPageUrl("moz-extension://generated-id/other.html"))
        assertFalse(policy.isTrustedPageUrl("https://home.example/live.html"))
    }

    @Test fun nonceMayBeConsumedOnlyOnceWhileItsGenerationIsVisible() {
        val gate = TouchLiveCameraCapabilityGate()
        val capability = gate.begin("camera.entryway", firstNonce)
        assertTrue(gate.accepts(capability.generation, "camera.entryway", firstNonce, visible = true))
        assertTrue(gate.consume(capability.generation, "camera.entryway", firstNonce, visible = true))
        assertFalse(gate.consume(capability.generation, "camera.entryway", firstNonce, visible = true))
    }

    @Test fun staleNonceWrongEntityAndHiddenSurfaceAreRefused() {
        val gate = TouchLiveCameraCapabilityGate()
        val first = gate.begin("camera.entryway", firstNonce)
        val second = gate.begin("camera.backyard", secondNonce)
        assertFalse(gate.accepts(first.generation, "camera.entryway", firstNonce, visible = true))
        assertFalse(gate.accepts(second.generation, "camera.entryway", secondNonce, visible = true))
        assertFalse(gate.accepts(second.generation, "camera.backyard", secondNonce, visible = false))
        assertTrue(gate.accepts(second.generation, "camera.backyard", secondNonce, visible = true))
        gate.revoke()
        assertNull(gate.current())
    }

    @Test fun consumedCapabilityRemainsIdentifiableOnlyUntilRevoked() {
        val gate = TouchLiveCameraCapabilityGate()
        val issued = gate.begin("camera.entryway", firstNonce)
        assertTrue(gate.consume(issued.generation, issued.entityId, issued.nonce, visible = true))
        assertTrue(gate.isCurrent(issued.generation, issued.entityId, issued.nonce, visible = true))
        gate.revoke()
        assertFalse(gate.isCurrent(issued.generation, issued.entityId, issued.nonce, visible = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortNonceCannotCreateACapability() {
        TouchLiveCameraCapabilityGate().begin("camera.entryway", "too-short")
    }
}
