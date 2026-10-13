package com.wyattfleming.touchframe.media

import android.content.Context
import com.wyattfleming.frameos.photos.FrameOfflineReserve
import com.wyattfleming.frameos.photos.FramePhotoBridge
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Activity-owned, non-View Photos reserve. It permits authenticated companion policy
 * updates before a media surface exists; callers must supply the already validated raw
 * Photos URL and profile. All reserve mutation shares one worker and scope generation.
 */
class TouchOfflinePhotoStore(
    context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "touchframe-offline-photos").apply { isDaemon = true }
    },
) : AutoCloseable {
    internal val reserve = FrameOfflineReserve(File(context.filesDir, "touchframe-offline-photos"))
    private val scopeGeneration = AtomicLong(0L)
    @Volatile private var requestedScope: TouchOfflineStoreScope? = null
    @Volatile private var closed = false

    fun activateScope(validatedPhotosUrl: String, profile: String): Future<Boolean> {
        val requested = TouchOfflineStoreScope(validatedPhotosUrl, profile)
        val expected = beginScope(requested)
        return submit {
            !closed && scopeGeneration.get() == expected && reserve.activateScope(validatedPhotosUrl, profile) &&
                !closed && scopeGeneration.get() == expected
        }
    }

    fun applyHiddenAssets(assetIds: Set<String>, canCommit: () -> Boolean = { true }): Future<Boolean> {
        val expected = scopeGeneration.get()
        return submit {
            !closed && scopeGeneration.get() == expected && reserve.applyHiddenAssets(assetIds) {
                !closed && scopeGeneration.get() == expected && canCommit()
            }
        }
    }

    /** Lock-free status published by reserve mutations; safe for companion UI polling. */
    fun publishedStatus(): FrameOfflineReserve.Status = reserve.publishedStatus()

    internal fun prepareBridge(
        bridge: FramePhotoBridge,
        session: GeckoSession,
        validatedPhotosUrl: String,
        profile: String,
        isCurrent: () -> Boolean,
    ): Future<Boolean> {
        val expected = beginScope(TouchOfflineStoreScope(validatedPhotosUrl, profile))
        return submit {
            !closed && isCurrent() && scopeGeneration.get() == expected &&
                bridge.configure(session, validatedPhotosUrl, profile) &&
                !closed && isCurrent() && scopeGeneration.get() == expected
        }
    }

    private fun beginScope(scope: TouchOfflineStoreScope): Long {
        if (requestedScope == scope) return scopeGeneration.get()
        requestedScope = scope
        return scopeGeneration.incrementAndGet()
    }

    internal fun execute(action: () -> Unit): Boolean = try {
        worker.execute { if (!closed) action() }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun <T> submit(action: () -> T): Future<T> = try {
        worker.submit<T> { if (closed) throw IllegalStateException("offline photo store is closed") else action() }
    } catch (error: RejectedExecutionException) {
        throw IllegalStateException("offline photo store is closed", error)
    }

    override fun close() {
        if (closed) return
        closed = true
        requestedScope = null
        scopeGeneration.incrementAndGet()
        worker.shutdownNow()
    }
}

internal data class TouchOfflineStoreScope(val url: String, val profile: String)
