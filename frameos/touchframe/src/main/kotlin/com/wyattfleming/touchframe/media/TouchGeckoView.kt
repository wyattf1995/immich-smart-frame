package com.wyattfleming.touchframe.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.mozilla.geckoview.GeckoView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GeckoView whose TextureView backend draws only when the page changes.
 *
 * GeckoView 144 and 154 hand Gecko a new Surface after every frame drawn into the TextureView
 * (SurfaceViewWrapper.ListenerWrapper.onSurfaceTextureUpdated). Gecko rebuilds its window surface and draws
 * again, so a still photo redrew as fast as the panel allows: a new EGL surface every 45 ms, about 23 fps and
 * 91% CPU on the 159K (10/17). Gecko's per-frame Surface exists for [requestNewSurface], its recovery after the
 * old surface is lost, so a frame update reaches Gecko only to answer that request.
 */
// Created in code by TouchMediaView and TouchLiveCameraSurface; never inflated from XML.
@SuppressLint("ViewConstructor")
internal class TouchGeckoView(context: Context) : GeckoView(context) {
    private val gate = TouchTextureFrameGate()

    override fun setViewBackend(backend: Int) {
        super.setViewBackend(backend)
        if (backend != BACKEND_TEXTURE_VIEW) return
        val texture = (0 until childCount).map(::getChildAt).filterIsInstance<TextureView>().singleOrNull() ?: return
        val gecko = texture.surfaceTextureListener ?: return
        texture.surfaceTextureListener = TouchGatedTextureListener(gecko, gate)
    }

    override fun requestNewSurface() {
        gate.requestNewSurface()
        super.requestNewSurface()
    }
}

/** Which TextureView frame updates reach Gecko; see [TouchGeckoView]. */
internal class TouchTextureFrameGate {
    // Gecko may ask from its compositor callbacks; TextureView callbacks arrive on the UI thread.
    private val newSurfaceRequested = AtomicBoolean(false)

    fun requestNewSurface() = newSurfaceRequested.set(true)

    /** A new SurfaceTexture is a new surface, which answers any pending request. */
    fun surfaceAvailable() = newSurfaceRequested.set(false)

    fun passFrameUpdate(): Boolean = newSurfaceRequested.getAndSet(false)
}

/** Passes Gecko every TextureView callback except a frame update it did not ask for. */
internal class TouchGatedTextureListener(
    private val gecko: TextureView.SurfaceTextureListener,
    private val gate: TouchTextureFrameGate,
) : TextureView.SurfaceTextureListener {
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        gate.surfaceAvailable()
        gecko.onSurfaceTextureAvailable(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
        gecko.onSurfaceTextureSizeChanged(surface, width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = gecko.onSurfaceTextureDestroyed(surface)

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (gate.passFrameUpdate()) gecko.onSurfaceTextureUpdated(surface)
    }
}
