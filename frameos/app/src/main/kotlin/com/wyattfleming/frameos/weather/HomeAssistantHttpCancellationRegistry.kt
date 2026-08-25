package com.wyattfleming.frameos.weather

import java.net.HttpURLConnection

class HomeAssistantHttpCancellationRegistry {
    private val lock = Any()
    private val connections = mutableSetOf<HttpURLConnection>()
    private val threadConnection = ThreadLocal<HttpURLConnection?>()
    private var cancellationGeneration = 0L

    fun beginRequest(): Long = synchronized(lock) { cancellationGeneration }

    fun register(requestGeneration: Long, connection: HttpURLConnection): Boolean = synchronized(lock) {
        if (requestGeneration != cancellationGeneration || Thread.currentThread().isInterrupted) {
            connection.disconnect()
            false
        } else {
            connections += connection
            threadConnection.set(connection)
            true
        }
    }

    fun unregister(connection: HttpURLConnection) = synchronized(lock) {
        connections -= connection
        if (threadConnection.get() === connection) threadConnection.remove()
    }

    fun isCurrent(requestGeneration: Long): Boolean = synchronized(lock) {
        val current = requestGeneration == cancellationGeneration && !Thread.currentThread().isInterrupted
        if (!current) threadConnection.get()?.disconnect()
        current
    }

    fun cancelAll() {
        val active = synchronized(lock) {
            cancellationGeneration += 1
            connections.toList().also { connections.clear() }
        }
        active.forEach(HttpURLConnection::disconnect)
    }
}
