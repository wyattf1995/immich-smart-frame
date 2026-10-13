package com.wyattfleming.touchframe.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

class TouchTaskScopeTest {
    @Test
    fun `calendar replacement burst retains only latest pending task`() {
        val worker = TouchTaskScope.newSingleWorker()
        val scope = TouchTaskScope()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loaded = AtomicInteger(-1)
        try {
            worker.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scope.resume()
            var prior: Future<*>? = null
            repeat(1000) { range ->
                prior?.cancel(true)
                prior = scope.submit(worker) { loaded.set(range) }
                assertEquals(1, worker.queue.size)
            }
            assertEquals(-1, loaded.get())
            release.countDown()
            prior!!.get(2, TimeUnit.SECONDS)
            assertEquals(999, loaded.get())
            assertEquals(0, worker.queue.size)
        } finally {
            release.countDown()
            scope.close()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `pause between registration and enqueue leaves no canceled wrapper`() {
        val scope = TouchTaskScope()
        val pauseBeforeEnqueue = AtomicBoolean(false)
        val worker = object : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()) {
            override fun execute(command: Runnable) {
                if (pauseBeforeEnqueue.get()) scope.pause()
                super.execute(command)
            }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val called = AtomicBoolean(false)
        try {
            worker.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scope.resume()
            pauseBeforeEnqueue.set(true)
            val task = scope.submit(worker) { called.set(true) }
            assertTrue(task!!.isCancelled)
            assertEquals(0, worker.queue.size)
            assertEquals(0, scope.trackedTaskCountForTest())
            assertFalse(called.get())
        } finally {
            release.countDown()
            scope.close()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `activity calendar worker exposes cancellation queue removal`() {
        val source = File("touchframe/src/main/kotlin/com/wyattfleming/touchframe/TouchFrameActivity.kt").readText()
        assertTrue(source.contains("private val worker = TouchTaskScope.newSingleWorker()"))
    }

    @Test
    fun `queued task paused before execution never invokes its block`() {
        val queued = QueueExecutor()
        val scope = TouchTaskScope()
        val called = AtomicBoolean(false)
        scope.resume()

        val future = scope.submit(queued) { called.set(true) }
        scope.pause()
        queued.runAll()

        assertNotNull(future)
        assertTrue(future!!.isCancelled)
        assertFalse(called.get())
        assertEquals(0, scope.trackedTaskCountForTest())
    }

    @Test
    fun `pause interrupts a running task`() {
        val worker = Executors.newSingleThreadExecutor()
        val scope = TouchTaskScope()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        try {
            scope.resume()
            val future = scope.submit(worker) {
                entered.countDown()
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(5))
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    Thread.currentThread().interrupt()
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            scope.pause()
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertTrue(future!!.isCancelled)
        } finally {
            scope.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `result predicate from a paused epoch stays false after resume`() {
        val queued = QueueExecutor()
        val scope = TouchTaskScope()
        var captured: (() -> Boolean)? = null
        scope.resume()

        val future = scope.submit(queued) { isCurrent -> captured = isCurrent }
        queued.runAll()
        assertNotNull(future)
        assertTrue(captured!!.invoke())

        scope.pause()
        scope.resume()

        assertFalse(captured!!.invoke())
        assertEquals(0, scope.trackedTaskCountForTest())
    }

    @Test
    fun `completed task is removed and closed scope rejects later work`() {
        val scope = TouchTaskScope()
        scope.resume()
        val future = scope.submit(Executor { runnable -> runnable.run() }) { }
        assertNotNull(future)
        assertTrue(future!!.isDone)
        assertEquals(0, scope.trackedTaskCountForTest())

        scope.close()
        assertNull(scope.submit(Executor { runnable -> runnable.run() }) { })
    }

    @Test
    fun `throwing task completes exceptionally and is removed`() {
        val scope = TouchTaskScope()
        scope.resume()

        val future = scope.submit(Executor { runnable -> runnable.run() }) {
            throw IllegalStateException("expected test failure")
        }

        assertNotNull(future)
        try {
            future!!.get()
            throw AssertionError("expected task exception")
        } catch (error: ExecutionException) {
            assertTrue(error.cause is IllegalStateException)
        }
        assertEquals(0, scope.trackedTaskCountForTest())
    }

    private class QueueExecutor : Executor {
        private val queued = java.util.ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued.addLast(command)
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }
}
