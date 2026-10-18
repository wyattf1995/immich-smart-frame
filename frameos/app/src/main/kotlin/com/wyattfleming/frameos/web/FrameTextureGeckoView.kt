package com.wyattfleming.frameos.web

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
 * again, so a still page redraws as fast as the panel allows (a still photo cost 91% CPU on the 159K, 10/17).
 * Gecko's per-frame Surface exists for [requestNewSurface], its recovery after the old surface is lost, so a
 * frame update reaches Gecko only to answer that request. The SurfaceView backend never loops.
 */
// Created in code by MainActivity and extended by FrameOAuthWebSurface; never inflated from XML.
@SuppressLint("ViewConstructor")
open class FrameTextureGeckoView(context: Context) : GeckoView(context) {
    private val gate = FrameTextureFrameGate()

    override fun setViewBackend(backend: Int) {
        super.setViewBackend(backend)
        if (backend != BACKEND_TEXTURE_VIEW) return
        val texture = (0 until childCount).map(::getChildAt).filterIsInstance<TextureView>().singleOrNull() ?: return
        val gecko = texture.surfaceTextureListener ?: return
        texture.surfaceTextureListener = FrameGatedTextureListener(gecko, gate)
    }

    override fun requestNewSurface() {
        gate.requestNewSurface()
        super.requestNewSurface()
    }
}

/** Which TextureView frame updates reach Gecko; see [FrameTextureGeckoView]. */
internal class FrameTextureFrameGate {
    // Gecko may ask from its compositor callbacks; TextureView callbacks arrive on the UI thread.
    private val newSurfaceRequested = AtomicBoolean(false)

    fun requestNewSurface() = newSurfaceRequested.set(true)

    /** A new SurfaceTexture is a new surface, which answers any pending request. */
    fun surfaceAvailable() = newSurfaceRequested.set(false)

    fun passFrameUpdate(): Boolean = newSurfaceRequested.getAndSet(false)
}

/** Passes Gecko every TextureView callback except a frame update it did not ask for. */
internal class FrameGatedTextureListener(
    private val gecko: TextureView.SurfaceTextureListener,
    private val gate: FrameTextureFrameGate,
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
