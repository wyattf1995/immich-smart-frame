package com.wyattfleming.frameos.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCompanionEventStoreTest {
    @Test
    fun `claim result distinguishes persisted duplicate and failed persistence`() {
        val persistence = FakePersistence()
        val store = FrameCompanionEventStore.forTest(persistence)

        assertEquals(FrameCompanionEventStore.ClaimResult.CLAIMED, store.claimResult("event-1", 1_000L))
        assertEquals(FrameCompanionEventStore.ClaimResult.ALREADY_HANDLED, store.claimResult("event-1", 1_001L))
        assertEquals(1, persistence.writes)

        val failing = FrameCompanionEventStore.forTest(FakePersistence(writesSucceed = false))
        assertEquals(FrameCompanionEventStore.ClaimResult.PERSISTENCE_FAILED, failing.claimResult("event-2", 1_000L))
    }

    @Test
    fun `legacy claim remains true only for a newly persisted event`() {
        val persistence = FakePersistence()
        val store = FrameCompanionEventStore.forTest(persistence)

        assertTrue(store.claim("event-1", 1_000L))
        assertFalse(store.claim("event-1", 1_001L))
        assertFalse(FrameCompanionEventStore.forTest(FakePersistence(writesSucceed = false)).claim("event-2", 1_000L))
    }

    private class FakePersistence(
        private val writesSucceed: Boolean = true,
        private var state: FrameCompanionEventStore.State = FrameCompanionEventStore.State(),
    ) : FrameCompanionEventStore.Persistence {
        var writes = 0
        override fun read(): FrameCompanionEventStore.State = state
        override fun write(state: FrameCompanionEventStore.State): Boolean {
            writes += 1
            if (writesSucceed) this.state = state
            return writesSucceed
        }
    }
}
