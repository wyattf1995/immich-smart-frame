package com.wyattfleming.frameos.control

import android.content.Context

class FrameCompanionCommandStore(context: Context) {
    private val preferences = context.getSharedPreferences("frameos_companion_commands", Context.MODE_PRIVATE)

    @Synchronized fun claim(id: String): Boolean {
        val key = commandKey(id)
        if (preferences.contains(key)) return false
        preferences.edit().putBoolean(key, true).apply()
        return true
    }

    @Synchronized fun ack(id: String, status: String, message: String? = null) {
        preferences.edit().remove(commandKey(id)).putString(ackKey(id), "$status|${message.orEmpty()}").apply()
    }

    @Synchronized fun consumeAcks(): List<FrameRemoteAck> {
        val interrupted = preferences.all.keys.filter { it.startsWith(COMMAND_PREFIX) }
        if (interrupted.isNotEmpty()) {
            preferences.edit().apply {
                interrupted.forEach { key ->
                    val id = key.removePrefix(COMMAND_PREFIX)
                    remove(key)
                    putString(ackKey(id), "failed|interrupted before acknowledgement")
                }
            }.apply()
        }
        return preferences.all.filterKeys { it.startsWith(ACK_PREFIX) }.map { (key, value) ->
            val stored = value.toString()
            FrameRemoteAck(key.removePrefix(ACK_PREFIX), stored.substringBefore('|'), stored.substringAfter('|').ifBlank { null })
        }
    }

    @Synchronized fun clearAcks(acks: List<FrameRemoteAck>) {
        preferences.edit().apply { acks.forEach { remove(ackKey(it.id)) } }.apply()
    }

    private fun commandKey(id: String) = "$COMMAND_PREFIX$id"
    private fun ackKey(id: String) = "$ACK_PREFIX$id"

    private companion object {
        const val COMMAND_PREFIX = "command:"
        const val ACK_PREFIX = "ack:"
    }
}
