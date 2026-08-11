package com.konductor.session

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoOpSessionStoreTest {
    @Test
    fun `candidate publication is explicit no IO and remains unlocatable`() {
        val session = NoOpSessionStore.newCandidate(Path.of("project"), "model", "ephemeral")
        session.promptAgentName = "bound-candidate"

        NoOpSessionStore.persistNew(session)

        assertEquals("bound-candidate", session.promptAgentName)
        assertNull(NoOpSessionStore.locate(session))
        assertTrue(NoOpSessionStore.listForCwd(session.cwd).isEmpty())
        assertFailsWith<UnsupportedOperationException> { NoOpSessionStore.loadHeader(session.id) }
        assertFailsWith<UnsupportedOperationException> { NoOpSessionStore.load(session.id) }
    }

    @Test
    fun persistMetadataPerformsNoIoOrLiveMutationWhileRenameStillCommits() {
        val session = NoOpSessionStore.persistedCandidate(Path.of("project"), "old-model", null)
        val candidate = session.metadata.copy(modelName = "new-model")

        NoOpSessionStore.persistMetadata(session, candidate)
        NoOpSessionStore.rewrite(session, emptyList())

        assertEquals("old-model", session.modelName)
        assertNull(session.name)

        NoOpSessionStore.rename(session, "renamed")

        assertEquals("renamed", session.name)
        assertEquals("old-model", session.modelName)
        assertNull(NoOpSessionStore.locate(session))
    }
}
