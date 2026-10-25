package com.wyattfleming.frameos.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every FrameOS GeckoView on the TextureView backend goes through the frame gate (see FrameTextureFrameGateTest). */
class FrameTextureGeckoViewSourceTest {
    private val sources = File("src/main/kotlin/com/wyattfleming/frameos")
    private val view = File(sources, "web/FrameTextureGeckoView.kt").readText()
    private val activity = File(sources, "MainActivity.kt").readText()
    private val oauth = File(sources, "web/FrameOAuthWebSurface.kt").readText()

    private fun body(source: String, start: String): String {
        val from = source.indexOf(start)
        assertTrue("missing $start", from >= 0)
        return source.substring(from, source.indexOf("\n    }\n", from))
    }

    @Test
    fun `the TextureView passes Gecko every callback except a frame update it did not ask for`() {
        val available = body(view, "override fun onSurfaceTextureAvailable(")
        assertTrue(available.indexOf("gate.surfaceAvailable()") in 0 until available.indexOf("gecko.onSurfaceTextureAvailable(surface, width, height)"))
        assertTrue(view.contains("gecko.onSurfaceTextureSizeChanged(surface, width, height)"))
        assertTrue(view.contains("gecko.onSurfaceTextureDestroyed(surface)"))
        assertTrue(body(view, "override fun onSurfaceTextureUpdated(").contains("if (gate.passFrameUpdate()) gecko.onSurfaceTextureUpdated(surface)"))
    }

    @Test
    fun `the gate wraps Gecko's own listener whenever the TextureView backend is chosen`() {
        val backend = body(view, "override fun setViewBackend(")
        assertTrue(backend.indexOf("super.setViewBackend(backend)") in 0 until backend.indexOf("FrameGatedTextureListener("))
        assertTrue(backend.contains("if (backend != BACKEND_TEXTURE_VIEW) return"))
        assertTrue(backend.contains("texture.surfaceTextureListener = FrameGatedTextureListener(gecko, gate)"))
    }

    @Test
    fun `Gecko asking for a new surface lets the next frame update through`() {
        val request = body(view, "override fun requestNewSurface()")
        assertTrue(request.indexOf("gate.requestNewSurface()") in 0 until request.indexOf("super.requestNewSurface()"))
    }

    @Test
    fun `the Home Assistant view and the sign-in page are the TextureView Gecko views and both are gated`() {
        assertTrue(activity.contains("val warmHomeAssistantView = FrameTextureGeckoView(this).apply {"))
        assertTrue(oauth.contains(") : FrameTextureGeckoView(context) {"))
        val textureUsers = sources.walkTopDown().filter { it.extension == "kt" && it.readText().contains("BACKEND_TEXTURE_VIEW") }
            .map { it.name }.toList().sorted()
        assertEquals(listOf("FrameOAuthWebSurface.kt", "FrameTextureGeckoView.kt", "MainActivity.kt"), textureUsers)
    }
}
