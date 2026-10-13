package com.wyattfleming.frameos.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCompanionSnapshotRevisionTest {
    private val revision = "a".repeat(64)

    @Test fun `only an exact valid sent revision can accept an unchanged envelope`() {
        assertTrue(FrameCompanionSnapshotRevision.acceptsUnchanged(revision, revision))
        assertFalse(FrameCompanionSnapshotRevision.acceptsUnchanged(null, revision))
        assertFalse(FrameCompanionSnapshotRevision.acceptsUnchanged(revision, "b".repeat(64)))
        assertFalse(FrameCompanionSnapshotRevision.acceptsUnchanged(revision, "not-a-revision"))
    }

    @Test fun `invalid revisions are never sent or remembered`() {
        assertTrue(FrameCompanionSnapshotRevision.valid(revision))
        assertFalse(FrameCompanionSnapshotRevision.valid("A".repeat(64)))
        assertNull(FrameCompanionSnapshotRevision.forRequest("bad"))
    }
}
