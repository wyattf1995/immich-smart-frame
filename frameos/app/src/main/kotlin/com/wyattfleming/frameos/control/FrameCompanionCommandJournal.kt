package com.wyattfleming.frameos.control

/** Bounded durable command/ack state. A successful [claim] is committed before dispatch. */
class FrameCompanionCommandJournal(private val persistence: Persistence) {
    data class State(
        val claimed: List<String> = emptyList(),
        val completed: List<String> = emptyList(),
        val acknowledgements: List<FrameRemoteAck> = emptyList(),
    )
    interface Persistence { fun read(): State; fun write(value: State): Boolean }
    private var state = persistence.read().normalized()

    @Synchronized fun claim(id: String): Boolean {
        if (id in state.claimed || id in state.completed) return false
        val next = state.copy(claimed = state.claimed + id).normalized()
        if (!persistence.write(next)) return false
        state = next; return true
    }
    @Synchronized fun acknowledge(id: String, status: String, message: String? = null): Boolean {
        if (id !in state.claimed) return false
        val ack = FrameRemoteAck(id, status, message)
        val next = state.copy(claimed = state.claimed - id, completed = state.completed + id, acknowledgements = state.acknowledgements.filterNot { it.id == id } + ack).normalized()
        if (!persistence.write(next)) return false
        state = next; return true
    }
    @Synchronized fun pendingAcks(): List<FrameRemoteAck> {
        recoverInterruptedClaims()
        return state.acknowledgements.take(MAX_ACK_BATCH)
    }
    @Synchronized fun clearAcks(sent: List<FrameRemoteAck>) {
        val ids = sent.map { it.id }.toSet()
        val next = state.copy(acknowledgements = state.acknowledgements.filterNot { it.id in ids })
        if (persistence.write(next)) state = next
    }
    @Synchronized fun completedCount() = state.completed.size
    private fun recoverInterruptedClaims() {
        if (state.claimed.isEmpty()) return
        val failed = state.claimed.map { FrameRemoteAck(it, "failed", "interrupted before acknowledgement") }
        val next = state.copy(claimed = emptyList(), completed = state.completed + state.claimed, acknowledgements = state.acknowledgements + failed).normalized()
        if (persistence.write(next)) state = next
    }
    private fun State.normalized() = copy(
        claimed = claimed.distinct(),
        completed = completed.distinct().takeLast(MAX_COMPLETED),
        acknowledgements = acknowledgements.distinctBy { it.id },
    )
    companion object { const val MAX_COMPLETED = 128; const val MAX_ACK_BATCH = 16 }
}
