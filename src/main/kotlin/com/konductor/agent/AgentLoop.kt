package com.konductor.agent

import com.konductor.compaction.Compactor
import com.konductor.compaction.CompactionSettings
import com.konductor.compaction.ContextWindowTracker
import com.konductor.core.models.AgentContext
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.session.NoOpSessionStore
import com.konductor.session.SessionStore
import com.konductor.session.SessionSummary
import com.konductor.session.reconstructHistory
import kotlinx.coroutines.CancellationException
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
    private val store: SessionStore = NoOpSessionStore,
    session: Session = store.create(cwd = Path.of("").toAbsolutePath(), model = context.modelName, name = null),
    // Auto-compaction settings. Defaults to disabled so ACP sessions and unit tests keep their exact behavior;
    // the TUI path passes Configuration.compaction (enabled by default). `/compact` (compact()) works regardless.
    compaction: CompactionSettings = CompactionSettings(enabled = false),
) {
    /** The session this loop is currently recording into. Retargeted by [newSession]/[resume]. */
    var session: Session = session
        private set

    // Compaction (M4). The tracker holds the latest authoritative context size (fed by UsageReported) and
    // decides when to compact; the compactor reuses this loop's provider for the summarization turn.
    private val tracker = ContextWindowTracker(compaction)
    private val compactor = Compactor(provider, compaction)

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

        // Compaction check (M4): before asking the provider to run the turn, if the last reported context size
        // is over the reply-headroom threshold, summarize older turns into a CompactionEntry so the
        // reconstructed transcript sent below is smaller. reconstructHistory then slices to [summary + kept].
        // Best-effort: a summarization failure (a transient inference error, or a bound PromptAgent emitting a
        // stray tool call) must not fail the user's turn — skip compaction and let the turn proceed.
        if (tracker.shouldCompact()) {
            val entry = try {
                compactor.compact(session, tokensBefore = tracker.contextTokens)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                null
            }
            entry?.let {
                recordCompaction(it)
                emit(AgentEvent.Compacted(it))
            }
        }

        provider.runTurn(TurnRequest(context = context, history = reconstructHistory(session.entries)), toolExecutor)
            .collect { event ->
                // Emit exactly what we persist. AgentLoop owns parentId: it stamps every entry (user, tool
                // call/result, assistant) from the actually-persisted transcript, so the linear chain is
                // consistent. The assistant arrives pre-built by the provider (with a parentId from the
                // provider's own discarded working copy, whose ids never reach session.entries), so re-stamp it
                // here and emit the re-stamped copy so stored == emitted.
                val outgoing = when (event) {
                    is AgentEvent.UsageReported -> {
                        tracker.update(event.usage) // feed the context tracker so the next turn can decide
                        event
                    }
                    is AgentEvent.ToolCallStarted -> {
                        record(
                            ToolCallEntry(
                                id = Uuid.random(),
                                parentId = session.entries.lastOrNull()?.id,
                                timestamp = Clock.System.now(),
                                call = event.call,
                            ),
                        )
                        event
                    }
                    is AgentEvent.ToolCallCompleted -> {
                        record(
                            ToolResultEntry(
                                id = Uuid.random(),
                                parentId = session.entries.lastOrNull()?.id,
                                timestamp = Clock.System.now(),
                                result = event.result,
                            ),
                        )
                        event
                    }
                    is AgentEvent.TurnCompleted -> {
                        val assistant = event.assistant.copy(parentId = session.entries.lastOrNull()?.id)
                        record(assistant)
                        AgentEvent.TurnCompleted(assistant)
                    }
                    else -> event
                }
                emit(outgoing)
            }
    }.catch { error ->
        // Persistence I/O (store.append inside record) is fallible and runs in this flow — including the very
        // first record(UserEntry) before the provider starts. Surface any failure as a recoverable Failed event
        // (mirroring PromptProvider) so a disk/lock/permission error fails the turn, not the whole app. catch is
        // exception-transparent, so cancellation still propagates.
        emit(AgentEvent.Failed(error))
    }

    /**
     * Compact on demand (`/compact [instructions]`): summarize older turns now, regardless of the auto-compaction
     * setting or the current context size. Records + persists the [CompactionEntry] and returns it (or null when
     * there was nothing worth summarizing). Resets the tracker so the next turn re-establishes the real size.
     */
    suspend fun compact(instructions: String? = null): CompactionEntry? {
        val entry = compactor.compact(session, instructions, tracker.contextTokens) ?: return null
        recordCompaction(entry)
        return entry
    }

    /** Start a fresh, empty session in the same store + cwd, and make it active. */
    fun newSession(): Session = store.create(session.cwd, context.modelName, name = null).also {
        session = it
        tracker.reset() // a fresh transcript carries no context size; drop the previous session's total
    }

    /** Load a persisted session by [id] and make it the active transcript. */
    fun resume(id: Uuid): Session = store.load(id).also {
        session = it
        tracker.reset() // drop the previous session's total; the next turn re-establishes it from usage
    }

    /** Rename the active session and persist the new label. */
    fun rename(name: String) = store.rename(session, name)

    /** Persist the active session's header after a caller mutates its metadata (e.g. `session.promptAgentName`).
     *  Kept generic so the loop stays agnostic to what the header carries. */
    fun persistSessionHeader() = store.persistHeader(session)

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

    /**
     * Insert a compaction marker at its `firstKeptEntryId` position — so the in-memory (and, after the rewrite,
     * on-disk) order is `[summarized…, marker, kept…]`, which is what [reconstructHistory] slices on — then
     * rewrite the persisted transcript and reset the tracker (the next turn re-establishes the reduced size).
     */
    private fun recordCompaction(entry: CompactionEntry) {
        val insertIndex = session.entries.indexOfFirst { it.id == entry.firstKeptEntryId }
            .let { if (it < 0) session.entries.size else it }
        session.entries.add(insertIndex, entry)
        store.rewrite(session)
        tracker.reset()
    }
}
