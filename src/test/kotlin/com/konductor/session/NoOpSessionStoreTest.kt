package com.konductor.session

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoOpSessionStoreTest {
    @Test
    fun persistMetadataPerformsNoIoOrLiveMutationWhileRenameStillCommits() {
        val session = NoOpSessionStore.create(Path.of("project"), "old-model", null)
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
