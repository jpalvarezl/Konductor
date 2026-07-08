package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * A non-persisting [SessionStore] for `--no-session` and tests. It deliberately keeps **no** state: the live
 * transcript lives in the returned [Session]'s `entries` list (mutated by the caller, e.g. `AgentLoop`), which
 * is the in-memory source of truth for a run. This store therefore just mints sessions and no-ops the rest —
 * [append] writes nothing, [load]/[listForCwd] have nothing to return. Cross-session lookup is intentionally
 * unsupported here (that is `JsonlSessionStore`'s job); `--no-session` means "ephemeral, never persist".
 */
object NoOpSessionStore : SessionStore {
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
        throw UnsupportedOperationException("ephemeral sessions are not persisted; nothing to load ($id)")

    override fun listForCwd(cwd: Path): List<SessionSummary> = emptyList()

    override fun rename(session: Session, name: String) {
        session.name = name
    }
}
