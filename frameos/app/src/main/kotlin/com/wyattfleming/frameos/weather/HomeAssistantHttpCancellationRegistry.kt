package com.wyattfleming.frameos.weather

import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

class HomeAssistantHttpRequestHandle(
    private val connection: HttpURLConnection,
) {
    private val termination = AtomicReference<Termination?>(null)

    fun cancel() = terminate(Termination.CANCELLED)

    fun expireDeadline() = terminate(Termination.DEADLINE)

    fun isCancelled(): Boolean = termination.get() == Termination.CANCELLED

    fun isDeadlineExpired(): Boolean = termination.get() == Termination.DEADLINE

    private fun terminate(reason: Termination) {
        if (termination.compareAndSet(null, reason)) connection.disconnect()
    }

    private enum class Termination {
        CANCELLED,
        DEADLINE,
    }
}

class HomeAssistantHttpCancellationRegistry {
    private val lock = Any()
    private val requests = mutableSetOf<HomeAssistantHttpRequestHandle>()
    private val threadRequest = ThreadLocal<HomeAssistantHttpRequestHandle?>()
    private var cancellationGeneration = 0L

    fun beginRequest(): Long = synchronized(lock) { cancellationGeneration }

    fun register(requestGeneration: Long, connection: HttpURLConnection): Boolean =
        register(requestGeneration, HomeAssistantHttpRequestHandle(connection))

    fun register(requestGeneration: Long, request: HomeAssistantHttpRequestHandle): Boolean = synchronized(lock) {
        if (requestGeneration != cancellationGeneration || Thread.currentThread().isInterrupted) {
            request.cancel()
            false
        } else {
            requests += request
            threadRequest.set(request)
            true
        }
    }

    fun unregister(request: HomeAssistantHttpRequestHandle) = synchronized(lock) {
        requests -= request
        if (threadRequest.get() === request) threadRequest.remove()
    }

    fun isCurrent(requestGeneration: Long): Boolean = synchronized(lock) {
        val current = requestGeneration == cancellationGeneration && !Thread.currentThread().isInterrupted
        if (!current) threadRequest.get()?.cancel()
        current
    }

    fun cancelAll() {
        val active = synchronized(lock) {
            cancellationGeneration += 1
            requests.toList().also { requests.clear() }
        }
        active.forEach(HomeAssistantHttpRequestHandle::cancel)
    }
}
