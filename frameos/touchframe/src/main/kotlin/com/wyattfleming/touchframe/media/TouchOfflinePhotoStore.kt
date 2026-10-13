package com.wyattfleming.touchframe.media

import android.content.Context
import com.wyattfleming.frameos.photos.FrameOfflineReserve
import com.wyattfleming.frameos.photos.FramePhotoBridge
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
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
    private val scopeGate = TouchOfflineStoreScopeGate()
    @Volatile private var closed = false

    fun activateScope(validatedPhotosUrl: String, profile: String): Future<Boolean> {
        val requested = TouchOfflineStoreScope(validatedPhotosUrl, profile)
        val expected = beginScope(requested)
        return submit {
            !closed && scopeGate.isCurrent(expected) && reserve.activateScope(validatedPhotosUrl, profile) &&
                !closed && scopeGate.isCurrent(expected)
        }
    }

    /**
     * Cancelling the returned future prevents queued work and re-checks cancellation at
     * the reserve commit boundary. A transaction already past that atomic boundary may finish.
     */
    fun applyHiddenAssets(assetIds: Set<String>, canCommit: () -> Boolean = { true }): Future<Boolean> {
        val expected = scopeGate.current()
        if (closed) return completedFalse()
        lateinit var task: FutureTask<Boolean>
        task = FutureTask(Callable {
            !closed && scopeGate.isCurrent(expected) && reserve.applyHiddenAssets(assetIds) {
                !task.isCancelled && !Thread.currentThread().isInterrupted &&
                    !closed && scopeGate.isCurrent(expected) && canCommit()
            }
        })
        return try {
            worker.execute(task)
            task
        } catch (_: RejectedExecutionException) {
            completedFalse()
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
            !closed && isCurrent() && scopeGate.isCurrent(expected) &&
                bridge.configure(session, validatedPhotosUrl, profile) &&
                !closed && isCurrent() && scopeGate.isCurrent(expected)
        }
    }

    private fun beginScope(scope: TouchOfflineStoreScope): Long {
        return scopeGate.begin(scope)
    }

    internal fun execute(action: () -> Unit): Boolean = try {
        worker.execute { if (!closed) action() }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun completedFalse(): Future<Boolean> {
        val task = FutureTask(Callable { false })
        task.run()
        return task
    }

    private fun <T> submit(action: () -> T): Future<T> = try {
        worker.submit<T> { if (closed) throw IllegalStateException("offline photo store is closed") else action() }
    } catch (error: RejectedExecutionException) {
        throw IllegalStateException("offline photo store is closed", error)
    }

    override fun close() {
        if (closed) return
        closed = true
        scopeGate.clear()
        worker.shutdownNow()
    }
}

internal data class TouchOfflineStoreScope(val url: String, val profile: String)

/** Serializes scope replacement decisions before reserve worker tasks run. */
internal class TouchOfflineStoreScopeGate {
    private val generation = AtomicLong(0L)
    private var requestedScope: TouchOfflineStoreScope? = null

    @Synchronized fun begin(scope: TouchOfflineStoreScope): Long {
        if (requestedScope == scope) return generation.get()
        requestedScope = scope
        return generation.incrementAndGet()
    }

    fun current(): Long = generation.get()
    fun isCurrent(expected: Long): Boolean = generation.get() == expected

    @Synchronized fun clear() {
        requestedScope = null
        generation.incrementAndGet()
    }
}
