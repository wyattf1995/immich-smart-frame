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
import java.util.concurrent.atomic.AtomicBoolean

class TouchTaskScopeTest {
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
