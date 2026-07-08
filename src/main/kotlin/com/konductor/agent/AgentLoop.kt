package com.konductor.agent

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.session.InMemorySessionStore
import com.konductor.session.SessionStore
import com.konductor.session.SessionSummary
import com.konductor.session.reconstructHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The agent-loop layer between the UI and the [AgentProvider]. It owns the transcript for a run: each
 * [runTurn] appends a [UserEntry], drives one provider turn to completion, and folds the resulting entries
 * back into the active [Session] so the next turn re-sends the full reconstructed transcript. Tool calls and
 * results are folded in **as they happen** (from `ToolCallStarted`/`ToolCallCompleted`), so a later turn can
 * see what the model did earlier — the provider only mutates its own in-turn working copy, so without this
 * the transcript would lose every tool interaction across turns.
 *
 * Persistence (M3): every produced [Entry] is also written to the injected [SessionStore] as it is appended,
 * so the transcript survives a restart. The default [store]/`session` are the ephemeral in-memory pair, so
 * callers that do not care about persistence (ACP sessions, unit tests) construct an [AgentLoop] unchanged.
 * [newSession]/[resume]/[rename] retarget the loop at a different session at runtime (the TUI slash-commands).
 *
 * Failed/partial turns: the [UserEntry] and any tool entries completed before the failure are kept (they
 * represent real actions taken); no assistant entry is persisted for a failed or partial turn (only a
 * terminal `TurnCompleted` contributes one). A dedicated failure entry is deferred.
 *
 * The coroutine `submit(text): Job` + cancellation shape (docs/spec/architecture.md#threading--concurrency)
 * lands in M6.
 */
class AgentLoop(
    private val provider: AgentProvider,
    private val toolExecutor: ToolExecutor,
    val context: AgentContext,
    private val store: SessionStore = InMemorySessionStore,
    session: Session = store.create(cwd = Path.of("").toAbsolutePath(), model = context.modelName, name = null),
) {
    /** The session this loop is currently recording into. Retargeted by [newSession]/[resume]. */
    var session: Session = session
        private set

    /** Transcript reconstructed so far (compaction-aware; identity until M4 produces compaction entries). */
    val history: List<Entry> get() = reconstructHistory(session.entries)

    val modelName: String get() = context.modelName

    /**
     * Run one user turn: append the [UserEntry], stream the provider's [AgentEvent]s, and fold the produced
     * entries (tool calls/results, then the completed assistant) into the session. The returned flow is cold —
     * collecting it drives the turn.
     */
    fun runTurn(userText: String): Flow<AgentEvent> = flow {
        record(
            UserEntry(
                id = Uuid.random(),
                parentId = session.entries.lastOrNull()?.id,
                timestamp = Clock.System.now(),
                text = userText,
            ),
        )

        provider.runTurn(TurnRequest(context = context, history = reconstructHistory(session.entries)), toolExecutor)
            .collect { event ->
                when (event) {
                    is AgentEvent.ToolCallStarted -> record(
                        ToolCallEntry(
                            id = Uuid.random(),
                            parentId = session.entries.lastOrNull()?.id,
                            timestamp = Clock.System.now(),
                            call = event.call,
                        ),
                    )
                    is AgentEvent.ToolCallCompleted -> record(
                        ToolResultEntry(
                            id = Uuid.random(),
                            parentId = session.entries.lastOrNull()?.id,
                            timestamp = Clock.System.now(),
                            result = event.result,
                        ),
                    )
                    // Re-stamp parentId onto the actually-persisted transcript: the provider built this entry
                    // with a parentId from its own (discarded) working copy of tool entries, whose random ids
                    // never reach session.entries. Chain to the real last entry to keep the linear-chain invariant.
                    is AgentEvent.TurnCompleted ->
                        record(event.assistant.copy(parentId = session.entries.lastOrNull()?.id))
                    else -> Unit
                }
                emit(event)
            }
    }.catch { error ->
        // Persistence I/O (store.append inside record) is fallible and runs in this flow — including the very
        // first record(UserEntry) before the provider starts. Surface any failure as a recoverable Failed event
        // (mirroring PromptProvider) so a disk/lock/permission error fails the turn, not the whole app. catch is
        // exception-transparent, so cancellation still propagates.
        emit(AgentEvent.Failed(error))
    }

    /** Start a fresh, empty session in the same store + cwd, and make it active. */
    fun newSession(): Session = store.create(session.cwd, context.modelName, name = null).also { session = it }

    /** Load a persisted session by [id] and make it the active transcript. */
    fun resume(id: Uuid): Session = store.load(id).also { session = it }

    /** Rename the active session and persist the new label. */
    fun rename(name: String) = store.rename(session, name)

    /** Sessions recorded for the active cwd, most-recently-updated first. */
    fun listSessions(): List<SessionSummary> = store.listForCwd(session.cwd)

    /** On-disk location of the active session, or `null` when it is not persisted. */
    fun sessionLocation(): Path? = store.locate(session)

    suspend fun close() = provider.close()

    /** Append [entry] to the active session's transcript and persist it (append-only). */
    private fun record(entry: Entry) {
        session.entries += entry
        store.append(session, entry)
    }
}
