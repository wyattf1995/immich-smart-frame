package com.wyattfleming.touchframe.concurrent

import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/**
 * Activity-owned lifecycle gate for bounded worker submissions.
 *
 * A task receives an epoch predicate it may carry into posted UI work. A paused
 * scope interrupts tasks already entered and makes every prior epoch stale; it
 * cannot retroactively stop a block which already began its own operation.
 */
class TouchTaskScope : AutoCloseable {
    private val lock = Any()
    private val tasks = LinkedHashSet<ScopedTask>()
    private var resumed = false
    private var closed = false
    private var epoch = 0L

    fun resume() {
        synchronized(lock) {
            if (!closed) resumed = true
        }
    }

    fun pause() {
        val pending = synchronized(lock) {
            if (closed || !resumed) return
            resumed = false
            epoch += 1L
            tasks.toList()
        }
        pending.forEach { it.cancel(true) }
    }

    fun submit(executor: Executor, block: (isCurrent: () -> Boolean) -> Unit): Future<*>? {
        val task: ScopedTask
        synchronized(lock) {
            if (closed || !resumed) return null
            val submissionEpoch = epoch
            task = ScopedTask(submissionEpoch, block)
            tasks += task
        }
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            synchronized(lock) { tasks.remove(task) }
            task.cancel(false)
            return null
        }
        return task
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            resumed = false
            epoch += 1L
            tasks.toList()
        }
        pending.forEach { it.cancel(true) }
    }

    internal fun trackedTaskCountForTest(): Int = synchronized(lock) { tasks.size }

    private fun isCurrent(submissionEpoch: Long): Boolean = synchronized(lock) {
        !closed && resumed && epoch == submissionEpoch
    }

    private inner class ScopedTask(
        private val submissionEpoch: Long,
        private val block: (isCurrent: () -> Boolean) -> Unit,
    ) : FutureTask<Unit>({
        if (isCurrent(submissionEpoch)) {
            block { isCurrent(submissionEpoch) }
        }
        Unit
    }) {
        override fun run() {
            if (isCancelled || !isCurrent(submissionEpoch)) {
                cancel(false)
                return
            }
            super.run()
        }

        override fun done() {
            synchronized(lock) { tasks.remove(this) }
        }
    }
}
