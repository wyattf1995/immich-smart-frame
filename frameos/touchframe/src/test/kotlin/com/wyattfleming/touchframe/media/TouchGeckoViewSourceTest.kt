package com.wyattfleming.touchframe.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every Touch GeckoView on the TextureView backend goes through the frame gate (see TouchTextureFrameGateTest). */
class TouchGeckoViewSourceTest {
    private val mediaDir = "touchframe/src/main/kotlin/com/wyattfleming/touchframe/media"
    private val view = File("$mediaDir/TouchGeckoView.kt").readText()

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
        assertTrue(backend.indexOf("super.setViewBackend(backend)") in 0 until backend.indexOf("TouchGatedTextureListener("))
        assertTrue(backend.contains("if (backend != BACKEND_TEXTURE_VIEW) return"))
        assertTrue(backend.contains("texture.surfaceTextureListener = TouchGatedTextureListener(gecko, gate)"))
    }

    @Test
    fun `Gecko asking for a new surface lets the next frame update through`() {
        val request = body(view, "override fun requestNewSurface()")
        assertTrue(request.indexOf("gate.requestNewSurface()") in 0 until request.indexOf("super.requestNewSurface()"))
    }

    @Test
    fun `Photos and the live camera are the only Gecko views and both use the gated view`() {
        val created = File(mediaDir).parentFile!!.walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> Regex("""(?<![A-Za-z])(Touch)?GeckoView\(context\)""").findAll(file.readText()).map { file.name to it.value } }
            .toList().sortedBy { it.first }
        // The plain GeckoView is constructed only as the gated view's superclass.
        assertEquals(listOf(
            "TouchGeckoView.kt" to "GeckoView(context)",
            "TouchLiveCameraSurface.kt" to "TouchGeckoView(context)",
            "TouchMediaView.kt" to "TouchGeckoView(context)",
        ), created)
    }
}
