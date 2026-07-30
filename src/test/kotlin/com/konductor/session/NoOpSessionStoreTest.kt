package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.SessionMetadata
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

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

    @Test
    fun rewriteFailsFastWhenAnImplementationDoesNotChooseItsBehavior() {
        val store = object : SessionStore {
            override val persistsSessions: Boolean = true

            override fun create(cwd: Path, model: String, name: String?): Session =
                NoOpSessionStore.create(cwd, model, name)

            override fun append(session: Session, entry: Entry) = Unit

            override fun load(id: Uuid): Session = throw UnsupportedOperationException()

            override fun listForCwd(cwd: Path): List<SessionSummary> = emptyList()

            override fun persistMetadata(session: Session, candidate: SessionMetadata) = Unit
        }
        val session = store.create(Path.of("project"), "model", null)

        assertFailsWith<UnsupportedOperationException> { store.rewrite(session, emptyList()) }
    }
}
