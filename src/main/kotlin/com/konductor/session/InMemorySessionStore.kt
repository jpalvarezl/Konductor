package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A non-persisting [SessionStore] for `--no-session` and tests. The transcript lives only in the returned
 * [Session]'s `entries` list (mutated by the caller); [append] is a no-op and nothing is written to disk.
 */
object InMemorySessionStore : SessionStore {
    override fun create(cwd: Path, model: String, name: String?): Session =
        Session(
            id = Uuid.random(),
            name = name,
            cwd = cwd.toAbsolutePath().normalize(),
            modelName = model,
            createdAt = Clock.System.now(),
        )

    override fun append(session: Session, entry: Entry) = Unit

    override fun load(id: Uuid): Session =
        throw UnsupportedOperationException("in-memory sessions are not persisted; nothing to load ($id)")

    override fun listForCwd(cwd: Path): List<SessionSummary> = emptyList()

    override fun rename(session: Session, name: String) {
        session.name = name
    }
}
