package com.wyattfleming.frameos.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class FrameCompanionCommandStore(context: Context) {
    private val preferences = context.getSharedPreferences("frameos_companion_commands", Context.MODE_PRIVATE)
    private val journal = FrameCompanionCommandJournal(object : FrameCompanionCommandJournal.Persistence {
        override fun read(): FrameCompanionCommandJournal.State = runCatching {
            val root = JSONObject(preferences.getString(JOURNAL_KEY, "{}") ?: "{}")
            FrameCompanionCommandJournal.State(root.optJSONArray("claimed").strings(), root.optJSONArray("completed").strings(), root.optJSONArray("acks").acks())
        }.getOrDefault(FrameCompanionCommandJournal.State())
        override fun write(value: FrameCompanionCommandJournal.State): Boolean = preferences.edit().putString(JOURNAL_KEY, JSONObject()
            .put("claimed", JSONArray(value.claimed)).put("completed", JSONArray(value.completed))
            .put("acks", JSONArray().apply { value.acknowledgements.forEach { put(JSONObject().put("id", it.id).put("status", it.status).put("message", it.message)) } }).toString()).commit()
    })
    fun claim(id: String) = journal.claim(id)
    fun ack(id: String, status: String, message: String? = null) { journal.acknowledge(id, status, message) }
    fun consumeAcks() = journal.pendingAcks()
    fun clearAcks(acks: List<FrameRemoteAck>) = journal.clearAcks(acks)
    private fun JSONArray?.strings() = this?.let { values -> List(values.length()) { values.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
    private fun JSONArray?.acks() = this?.let { values -> List(values.length()) { values.optJSONObject(it) }.mapNotNull { item -> item?.optString("id")?.takeIf { it.isNotBlank() }?.let { id -> FrameRemoteAck(id, item.optString("status"), item.optString("message").ifBlank { null }) } } } ?: emptyList()
    private companion object { const val JOURNAL_KEY = "journal" }
}
