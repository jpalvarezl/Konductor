package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.SessionMetadata
import java.nio.file.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Persistence seam for [Session]s (`docs/spec/sessions.md`). The transcript is client-owned, so the store
 * only records entries as they are produced and reconstructs them on resume — it never talks to the model.
 *
 * Two implementations ship in M3: [NoOpSessionStore] (`--no-session` and tests) and [JsonlSessionStore]
 * (append-only JSONL under `~/.konductor/sessions/`).
 */
interface SessionStore {
    /** Create a fresh, empty session for [cwd] and persist its header (so it is immediately listable). */
    fun create(cwd: Path, model: String, name: String?): Session

    /** Persist one newly produced [entry] (append-only: one JSONL line). */
    fun append(session: Session, entry: Entry)

    /** Load a previously persisted session by [id], reconstructing its full transcript. */
    fun load(id: Uuid): Session

    /** Summaries of the sessions recorded for [cwd], most-recently-updated first. */
    fun listForCwd(cwd: Path): List<SessionSummary>

    /** The most recently updated session for [cwd], or `null` if none exists (drives `--continue`). */
    fun mostRecentForCwd(cwd: Path): SessionSummary? = listForCwd(cwd).firstOrNull()

    /** Persist a renamed candidate, then commit it to [session]. */
    fun rename(session: Session, name: String) {
        val candidate = session.metadata.copy(name = name)
        persistMetadata(session, candidate)
        session.commitMetadata(candidate)
    }

    /**
     * Persist a complete header built from immutable [candidate] metadata while [session] still carries the accepted
     * live values. Implementations return after accepting the candidate or throw without requiring caller rollback.
     * Filesystem implementations document their exact durability guarantees separately.
     */
    fun persistMetadata(session: Session, candidate: SessionMetadata)

    /**
     * Persist [session]'s full transcript (header + every current entry, in order). Unlike [append], this can
     * reflect an insertion/reorder: compaction inserts a `CompactionEntry` mid-transcript (before the first kept
     * entry), so the file is rewritten to match the in-memory order that `reconstructHistory` expects. No-op for
     * non-persistent stores.
     */
    fun rewrite(session: Session) {}

    /** On-disk location of [session], or `null` for stores that do not persist (in-memory). */
    fun locate(session: Session): Path? = null
}

/**
 * Lightweight index entry for [SessionStore.listForCwd] — enough to render a picker without loading the
 * whole transcript.
 */
data class SessionSummary(
    val id: Uuid,
    val name: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val entryCount: Int,
)
