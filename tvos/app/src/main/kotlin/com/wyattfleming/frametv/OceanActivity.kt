package com.wyattfleming.frametv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.wyattfleming.touchframe.ocean.TouchOceanConnection
import com.wyattfleming.touchframe.ocean.TouchOceanLoad
import com.wyattfleming.touchframe.ocean.TouchOceanRepository
import com.wyattfleming.touchframe.ocean.TouchOceanState
import com.wyattfleming.touchframe.ocean.TouchOceanView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Main Beach full screen: FrameOS's shared Ocean page (air and sky, water temperature and swell, the next 24 hours of
 * tide) over its moving water, from Ebbward's public conditions with no account or token. Fresh conditions are reused
 * for ten minutes and a failed read retries in a minute, only while the page is open; the water moves only while it is
 * in front. The last good conditions are kept on disk, so a Wi‑Fi drop shows them with their time. Back returns home.
 */
class OceanActivity : Activity() {
    private lateinit var page: TouchOceanView
    private val repository by lazy { TouchOceanRepository(File(filesDir, "frame-ocean/conditions.json")) }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var state = TouchOceanState()
    private var request: Future<*>? = null
    private var resumed = false
    private val refreshVisible = Runnable { refresh() }

    /** The tide's "now" moves with the clock; the page re-derives itself at most once per five minutes. */
    private val clock = object : Runnable {
        override fun run() {
            page.tick()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvUi.window(this)
        page = TouchOceanView(TvScenes.context(this)) { refresh(force = true) }
        page.render(state)
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        page.setActive(true)
        handler.post(clock)
        refresh()
    }

    override fun onPause() {
        resumed = false
        page.setActive(false)
        handler.removeCallbacks(clock)
        handler.removeCallbacks(refreshVisible)
        super.onPause()
    }

    override fun onDestroy() {
        request?.cancel(true)
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun refresh(force: Boolean = false) {
        if (!resumed || request != null) return
        val now = System.currentTimeMillis()
        val fetched = state.fetchedAtMillis
        if (!force && state.connection == TouchOceanConnection.LIVE && fetched != null && now - fetched in 0 until REFRESH_MILLIS) {
            schedule(REFRESH_MILLIS - (now - fetched))
            return
        }
        state = state.copy(loading = true)
        page.render(state)
        request = worker.submit {
            val load = runCatching { repository.load(TouchOceanRepository.DEFAULT_SPOT, TIMEOUT_MILLIS) }
                .getOrElse { TouchOceanLoad(null, TouchOceanConnection.OFFLINE) }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                request = null
                val live = load.connection == TouchOceanConnection.LIVE
                state = TouchOceanState(
                    conditions = load.conditions ?: state.conditions,
                    connection = load.connection,
                    loading = false,
                    fetchedAtMillis = if (live) System.currentTimeMillis() else state.fetchedAtMillis,
                )
                page.render(state)
                schedule(if (live) REFRESH_MILLIS else RETRY_MILLIS)
            }
        }
    }

    private fun schedule(delayMillis: Long) {
        handler.removeCallbacks(refreshVisible)
        if (resumed) handler.postDelayed(refreshVisible, delayMillis.coerceAtLeast(1_000L))
    }

    private companion object {
        const val TICK_MILLIS = 30_000L
        const val REFRESH_MILLIS = 10 * 60_000L
        const val RETRY_MILLIS = 60_000L
        const val TIMEOUT_MILLIS = 15_000
    }
}
