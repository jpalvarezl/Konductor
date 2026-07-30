package com.konductor.agent

import com.konductor.compaction.Compactor
import com.konductor.compaction.CompactionSettings
import com.konductor.compaction.ContextWindowTracker
import com.konductor.compaction.TokenEstimator
import com.konductor.core.models.AgentContext
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ProviderSessionLifecycle
import com.konductor.provider.SessionHistoryOwnership
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
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Raised when a second turn is collected while this loop is still running a turn. */
class TurnAlreadyInProgressException :
    IllegalStateException("A turn is already in progress for this session.")

sealed interface CompactionResult {
    data class Completed(val entry: CompactionEntry?) : CompactionResult
    data object Unsupported : CompactionResult
}

sealed interface ModelSwitchResult {
    data class Switched(val previous: String, val current: String) : ModelSwitchResult
    data object Unsupported : ModelSwitchResult
    data class FixedByPromptAgent(val agentName: String) : ModelSwitchResult
    data class Invalid(val error: Exception) : ModelSwitchResult
}

/**
 * The agent-loop layer between the UI and the [AgentProvider]. It owns the transcript for a run: each
 * [runTurn] appends a [UserEntry], drives one provider turn to completion, and folds the resulting entries
 * back into the active [Session]. Client-owned history is reconstructed for Prompt; server-owned history sends only
 * the current user entry while retaining local entries as an activity transcript. Tool calls and
 * results are folded in **as they happen** (from `ToolCallStarted`/`ToolCallCompleted`), so a later turn can
 * see what the model did earlier — the provider only mutates its own in-turn working copy, so without this
 * the transcript would lose every tool interaction across turns.
 *
 * Persistence (M3): every produced [Entry] is also written to the injected [SessionStore] as it is appended,
 * so the transcript survives a restart. The default [store]/`session` are the ephemeral in-memory pair, so
 * callers that do not care about persistence (primarily unit tests) construct an [AgentLoop] unchanged.
 * [newSession]/[resume]/[rename] retarget the loop at a different session at runtime (the TUI slash-commands).
 *
 * Failed/partial turns: the [UserEntry] and any tool entries completed before the failure are kept (they
 * represent real actions taken); no assistant entry is persisted for a failed or partial turn (only a
 * terminal `TurnCompleted` contributes one). A dedicated failure entry is deferred.
 *
 * Turn concurrency: one [AgentLoop] is one mutable session, so [runTurn] is single-flight. A concurrent
 * collection is rejected with [TurnAlreadyInProgressException] rather than queued; this matches the TUI,
 * which makes prompt input inert while a turn is active, and avoids executing stale queued prompts.
 * Frontends own the collecting [kotlinx.coroutines.Job], so the active turn remains cancelable.
 */
class AgentLoop(
    private val runtime: ProviderRuntime,
    toolExecutor: ToolExecutor,
    context: AgentContext,
    private val store: SessionStore = NoOpSessionStore,
    session: Session = store.create(cwd = Path.of("").toAbsolutePath(), model = context.modelName, name = null),
    // Auto-compaction settings. Defaults to disabled so direct unit-test loops keep their exact behavior. The
    // provider capability is applied here, so a frontend cannot accidentally enable Prompt compaction for Hosted.
    compaction: CompactionSettings = CompactionSettings(enabled = false),
) {
    constructor(
        provider: AgentProvider,
        toolExecutor: ToolExecutor,
        context: AgentContext,
        store: SessionStore = NoOpSessionStore,
        session: Session = store.create(cwd = Path.of("").toAbsolutePath(), model = context.modelName, name = null),
        compaction: CompactionSettings = CompactionSettings(enabled = false),
    ) : this(ProviderRuntime(provider), toolExecutor, context, store, session, compaction)

    private val provider: AgentProvider = runtime.provider
    private val capabilities = runtime.capabilities
    private val sessionLifecycle = runtime.sessionLifecycle
    private val toolExecutor: ToolExecutor = if (capabilities.localTools) toolExecutor else NoToolExecutor
    private val effectiveCompaction = compaction.copy(enabled = compaction.enabled && capabilities.clientCompaction)
    /** The session this loop is currently recording into. Retargeted by [newSession]/[resume]. */
    var session: Session = session
        private set

    /** Context used for subsequent turns. `/model` updates the model while preserving prompts/tools. */
    var context: AgentContext = if (capabilities.localTools) context else context.copy(tools = emptyList())
        private set

    // Compaction (M4). The tracker holds the latest authoritative context size (fed by UsageReported) and
    // decides when to compact; the compactor reuses this loop's provider for the summarization turn. Hosted's
    // effective settings are disabled here regardless of what either frontend passes.
    private val tracker = ContextWindowTracker(effectiveCompaction)
    private val compactor = Compactor(provider, effectiveCompaction)
    private val turnMutex = Mutex()

    /** Transcript reconstructed so far, including the latest compaction summary + kept entries when present. */
    val history: List<Entry> get() = reconstructHistory(session.entries)

    val modelName: String get() = context.modelName

    /** Current provider capability used by command discovery; execution still enforces it in [compact]. */
    val clientCompactionAvailable: Boolean get() = capabilities.clientCompaction

    /**
     * Run one user turn: append the [UserEntry], stream the provider's [AgentEvent]s, and fold the produced
     * entries (tool calls/results, then the completed assistant) into the session. The returned flow is cold —
     * collecting it drives the turn. Only one collection may run at a time for this loop.
     */
    fun runTurn(userText: String): Flow<AgentEvent> = flow {
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            runTurnSingleFlight(userText).collect { emit(it) }
        } finally {
            turnMutex.unlock()
        }
    }

    private fun runTurnSingleFlight(userText: String): Flow<AgentEvent> = flow {
        activateForTurn()
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
        // Best-effort: a summarization failure (a transient Foundry Responses error, or a bound PromptAgent emitting a
        // stray tool call) must not fail the user's turn — skip compaction and let the turn proceed.
        if (tracker.shouldCompact()) {
            val entry = try {
                compactor.compact(session, tokensBefore = tokensBeforeCompaction())
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

        val providerHistory = when (capabilities.sessionHistoryOwnership) {
            SessionHistoryOwnership.Client -> reconstructHistory(session.entries)
            // The local JSONL transcript remains a user-visible activity record, but the Hosted server session is
            // authoritative. Send only this turn's user input rather than implying that local history is replayed.
            SessionHistoryOwnership.Server -> listOf(session.entries.last())
        }
        provider.runTurn(TurnRequest(context = context, history = providerHistory), toolExecutor)
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
     * setting or the current context size. Returns a typed unsupported/completed outcome; a completed result carries
     * the recorded [CompactionEntry], or null when there was nothing worth summarizing. Resets the tracker after work.
     */
    suspend fun compact(instructions: String? = null): CompactionResult {
        if (!capabilities.clientCompaction) return CompactionResult.Unsupported
        val entry = compactor.compact(session, instructions, tokensBeforeCompaction())
        entry?.let(::recordCompaction)
        return CompactionResult.Completed(entry)
    }

    /**
     * Prepare the session selected by process/bootstrap composition. New sessions reserve local Hosted identity only;
     * resume/continue reconnects before the frontend accepts a turn.
     */
    suspend fun activateInitialSession(resuming: Boolean) {
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            val binding = prepareBinding(session)
            when (val lifecycle = sessionLifecycle) {
                ProviderSessionLifecycle.None -> Unit
                is ProviderSessionLifecycle.Hosted -> {
                    if (resuming) lifecycle.controller.activate(binding, session.entries.isNotEmpty())
                }
            }
        } finally {
            turnMutex.unlock()
        }
    }

    /** Start a fresh session, reserve its durable Hosted identity, and detach the previous selection. */
    suspend fun newSession(): Session {
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            val candidate = store.create(session.cwd, context.modelName, name = null)
            prepareBinding(candidate)
            (sessionLifecycle as? ProviderSessionLifecycle.Hosted)?.controller?.detach()
            session = candidate
            tracker.reset()
            return candidate
        } finally {
            turnMutex.unlock()
        }
    }

    /** Reconnect a persisted Hosted binding before committing the loaded session as the active transcript. */
    suspend fun resume(id: Uuid): Session {
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            val candidate = store.load(id)
            val binding = prepareBinding(candidate)
            (sessionLifecycle as? ProviderSessionLifecycle.Hosted)?.controller
                ?.activate(binding, candidate.entries.isNotEmpty())
            session = candidate
            tracker.reset()
            return candidate
        } finally {
            turnMutex.unlock()
        }
    }

    /** Rename the active session and persist the new label. */
    fun rename(name: String) = store.rename(session, name)

    /**
     * Return the current presentation-neutral reason a model switch cannot run, or `null` when a deployment may be
     * validated and selected. Discovery callers use this before a service request; [switchModel] reuses the same
     * gate so Hosted and PromptAgent-fixed behavior have one authority.
     */
    fun modelSwitchRestriction(): ModelSwitchResult? {
        if (!capabilities.clientModelSwitching) return ModelSwitchResult.Unsupported
        val activeAgent = (runtime.management as? ProviderManagement.PromptAgents)?.binder?.activeAgent
        return activeAgent?.let(ModelSwitchResult::FixedByPromptAgent)
    }

    /** Switch the model when the configured runtime can honor it, returning a typed presentation-neutral outcome. */
    fun switchModel(modelName: String): ModelSwitchResult {
        modelSwitchRestriction()?.let { return it }

        return try {
            val normalized = modelName.trim()
            require(normalized.isNotEmpty()) { "Model name cannot be blank." }
            val previous = context.modelName
            val candidateContext = context.copy(modelName = normalized)
            val candidateMetadata = session.metadata.copy(modelName = normalized)
            store.persistMetadata(session, candidateMetadata)
            session.commitMetadata(candidateMetadata)
            context = candidateContext
            ModelSwitchResult.Switched(previous, normalized)
        } catch (error: Exception) {
            ModelSwitchResult.Invalid(error)
        }
    }

    /** Persist the active session's current header after a caller mutates metadata not owned by this loop. */
    fun persistSessionHeader() = store.persistMetadata(session, session.metadata)

    /** Sessions recorded for the active cwd, most-recently-updated first. */
    fun listSessions(): List<SessionSummary> = store.listForCwd(session.cwd)

    /** On-disk location of the active session, or `null` when it is not persisted. */
    fun sessionLocation(): Path? = store.locate(session)

    suspend fun close() = provider.close()

    /** Activate the exact selected binding before recording the user entry. */
    private suspend fun activateForTurn() {
        val binding = prepareBinding(session)
        when (val lifecycle = sessionLifecycle) {
            ProviderSessionLifecycle.None -> Unit
            is ProviderSessionLifecycle.Hosted -> lifecycle.controller.activate(binding, session.entries.isNotEmpty())
        }
    }

    /** Persist a deterministic Hosted binding before any remote create can happen. */
    private fun prepareBinding(target: Session): HostedSessionBinding? = when (val lifecycle = sessionLifecycle) {
        ProviderSessionLifecycle.None -> {
            require(target.hostedBinding == null) {
                "Session '${target.id}' is a Hosted v2 session and cannot be opened by a Prompt runtime."
            }
            null
        }
        is ProviderSessionLifecycle.Hosted -> {
            if (!store.persistsSessions) {
                require(target.hostedBinding == null) {
                    "An ephemeral Hosted session cannot adopt a persisted server binding."
                }
                null
            } else {
                target.hostedBinding ?: run {
                    require(target.entries.isEmpty()) {
                        "Session '${target.id}' predates durable Hosted bindings and has local history but no " +
                            "recoverable server session id. Start a new Hosted session."
                    }
                    require(target.promptAgentName == null) {
                        "PromptAgent session '${target.id}' cannot be converted into a Hosted session."
                    }
                    val binding = HostedSessionBinding(lifecycle.controller.hostedAgentName, target.id.toString())
                    val candidate = target.metadata.copy(hostedBinding = binding)
                    store.persistMetadata(target, candidate)
                    target.commitMetadata(candidate)
                    binding
                }
            }
        }
    }

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

    private fun tokensBeforeCompaction(): Int =
        tracker.contextTokens.takeIf { it > 0 } ?: TokenEstimator.estimateTokens(session.entries)
}
