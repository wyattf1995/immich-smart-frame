package com.wyattfleming.frameos.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCompanionCommandJournalTest {
    @Test
    fun `claim is durable before dispatch and survives restart after completion`() {
        val persistence = MemoryJournalPersistence()
        val first = FrameCompanionCommandJournal(persistence)
        assertTrue(first.claim("one"))
        assertTrue(first.acknowledge("one", "applied"))
        val restarted = FrameCompanionCommandJournal(persistence)
        assertFalse(restarted.claim("one"))
        assertEquals(listOf(FrameRemoteAck("one", "applied")), restarted.pendingAcks())
        restarted.clearAcks(restarted.pendingAcks())
        assertFalse(FrameCompanionCommandJournal(persistence).claim("one"))
    }

    @Test
    fun `claimed command interrupted by process death gets durable failed acknowledgement`() {
        val persistence = MemoryJournalPersistence()
        assertTrue(FrameCompanionCommandJournal(persistence).claim("one"))
        val restarted = FrameCompanionCommandJournal(persistence)
        assertEquals(listOf(FrameRemoteAck("one", "failed", "interrupted before acknowledgement")), restarted.pendingAcks())
        assertFalse(restarted.claim("one"))
    }

    @Test
    fun `acknowledgements remain until a valid response clears their sent batch`() {
        val journal = FrameCompanionCommandJournal(MemoryJournalPersistence())
        assertTrue(journal.claim("one")); journal.acknowledge("one", "applied")
        val sent = journal.pendingAcks()
        assertEquals(1, sent.size)
        assertEquals(sent, journal.pendingAcks())
        journal.clearAcks(sent)
        assertTrue(journal.pendingAcks().isEmpty())
    }

    @Test
    fun `journal bounds completed ids and acknowledgement batches`() {
        val journal = FrameCompanionCommandJournal(MemoryJournalPersistence())
        repeat(130) { index ->
            val id = "command-$index"
            assertTrue(journal.claim(id)); journal.acknowledge(id, "applied")
        }
        assertEquals(16, journal.pendingAcks().size)
        assertEquals(128, journal.completedCount())
        assertTrue(journal.claim("command-0"))
        assertFalse(journal.claim("command-129"))
    }

    private class MemoryJournalPersistence : FrameCompanionCommandJournal.Persistence {
        private var state = FrameCompanionCommandJournal.State()
        override fun read() = state
        override fun write(value: FrameCompanionCommandJournal.State): Boolean {
            state = value
            return true
        }
    }
}
