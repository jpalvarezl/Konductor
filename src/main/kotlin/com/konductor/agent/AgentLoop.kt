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
import com.konductor.core.models.requireValidPromptAgentName
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ProviderSessionLifecycle
import com.konductor.provider.SessionHistoryOwnership
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.PreparedPromptAgentBinding
import com.konductor.provider.inference.PromptContextOverflowException
import com.konductor.session.NoOpSessionStore
import com.konductor.session.SessionStore
import com.konductor.session.SessionSummary
import com.konductor.session.reconstructHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Raised when an operation starts while this loop is already running a turn, compaction, or session mutation. */
class TurnAlreadyInProgressException :
    IllegalStateException("Another turn or session operation is already in progress for this session.")

enum class ContextOverflowRecoveryStage {
    NO_COMPACTABLE_HISTORY,
    COMPACTION,
    RETRY,
}

/** SDK-free terminal failure for a reactive context-overflow recovery stage. */
class ContextOverflowRecoveryException(
    val stage: ContextOverflowRecoveryStage,
    cause: Throwable,
) : RuntimeException(
    when (stage) {
        ContextOverflowRecoveryStage.NO_COMPACTABLE_HISTORY ->
            "Prompt context overflow recovery found no compactable history."
        ContextOverflowRecoveryStage.COMPACTION ->
            "Prompt context overflow recovery failed during compaction."
        ContextOverflowRecoveryStage.RETRY ->
            "Prompt context overflow recovery retry failed."
    },
    cause,
)

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

/** Exact PromptAgent name committed across provider and live session state. */
data class PromptAgentBindingResult(
    val agentName: String?,
    /** False only when provider and session metadata already held this exact validated [agentName]. */
    val changed: Boolean,
)

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
 * Session-operation concurrency: one [AgentLoop] is one mutable session, so [runTurn] and manual [compact] are
 * single-flight with each other. A concurrent operation is rejected with [TurnAlreadyInProgressException] rather
 * than queued; this matches the TUI, which makes prompt input inert while work is active, and avoids executing work
 * against a stale transcript. Frontends own the collecting [kotlinx.coroutines.Job], so active work remains cancelable.
 */
class AgentLoop(
    private val runtime: ProviderRuntime,
    toolExecutor: ToolExecutor,
    context: AgentContext,
    private val store: SessionStore = NoOpSessionStore,
    session: Session = store.newCandidate(
        cwd = Path.of("").toAbsolutePath(),
        model = context.modelName,
        name = null,
    ).also(store::persistNew),
    // Auto-compaction settings. Defaults to disabled so direct unit-test loops keep their exact behavior. The
    // provider capability is applied here, so a frontend cannot accidentally enable Prompt compaction for Hosted.
    compaction: CompactionSettings = CompactionSettings(enabled = false),
) {
    constructor(
        provider: AgentProvider,
        toolExecutor: ToolExecutor,
        context: AgentContext,
        store: SessionStore = NoOpSessionStore,
        session: Session = store.newCandidate(
            cwd = Path.of("").toAbsolutePath(),
            model = context.modelName,
            name = null,
        ).also(store::persistNew),
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

    /** The committed PromptAgent provider route, or null for unmanaged/ephemeral Prompt. */
    val activePromptAgentName: String?
        get() = (runtime.management as? ProviderManagement.PromptAgents)?.binder?.activeAgent

    /** Current provider capability used by command discovery; execution still enforces it in [compact]. */
    val clientCompactionAvailable: Boolean get() = capabilities.clientCompaction

    /**
     * Run one user turn: append the [UserEntry], stream the provider's [AgentEvent]s, and fold the produced
     * entries (tool calls/results, then the completed assistant) into the session. The returned flow is cold —
     * collecting it drives the turn. Only one collection may run at a time for this loop.
     */
    fun runTurn(userText: String): Flow<AgentEvent> = flow {
        // Manual lock path: the lock must span collection of this cold Flow, not just construction of the Flow value.
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

        val recoveryCapable = capabilities.clientCompaction &&
            capabilities.sessionHistoryOwnership == SessionHistoryOwnership.Client
        var replaySafe = true
        var originalOverflow: PromptContextOverflowException? = null
        provider.runTurn(TurnRequest(context = context, history = providerHistory()), toolExecutor)
            .takeWhile { event ->
                val failure = (event as? AgentEvent.Failed)?.error
                if (failure is CancellationException) throw failure
                val overflow = failure as? PromptContextOverflowException
                if (overflow != null && recoveryCapable && replaySafe) {
                    originalOverflow = overflow
                    false
                } else {
                    true
                }
            }
            .collect { event ->
                // Retry status is the only event that leaves the first attempt replay-safe. Even an empty text delta
                // or a tool-call start is enough to prohibit replay.
                if (event !is AgentEvent.Retrying) replaySafe = false
                emit(foldProviderEvent(event))
            }

        val overflow = originalOverflow ?: return@flow
        val compactionEntry = try {
            compactor.compact(session, tokensBefore = tokensBeforeCompaction())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            emit(
                AgentEvent.Failed(
                    contextOverflowRecoveryFailure(ContextOverflowRecoveryStage.COMPACTION, error, overflow),
                ),
            )
            return@flow
        }
        if (compactionEntry == null) {
            emit(
                AgentEvent.Failed(
                    ContextOverflowRecoveryException(ContextOverflowRecoveryStage.NO_COMPACTABLE_HISTORY, overflow),
                ),
            )
            return@flow
        }

        try {
            recordCompaction(compactionEntry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            emit(
                AgentEvent.Failed(
                    contextOverflowRecoveryFailure(ContextOverflowRecoveryStage.COMPACTION, error, overflow),
                ),
            )
            return@flow
        }
        emit(AgentEvent.Compacted(compactionEntry))

        // The committed marker is valid durable state even if cancellation arrives here. Never start the retry after
        // the collecting turn has been cancelled.
        currentCoroutineContext().ensureActive()
        var retryFailure: Throwable? = null
        val retryEvents = try {
            provider.runTurn(TurnRequest(context = context, history = providerHistory()), toolExecutor)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            retryFailure = error
            null
        }
        retryEvents
            ?.catch { error ->
                if (error is CancellationException) throw error
                retryFailure = error
            }
            ?.transformWhile { event ->
                if (event is AgentEvent.Failed) {
                    if (event.error is CancellationException) throw event.error
                    retryFailure = event.error
                    false
                } else {
                    val outgoing = try {
                        foldProviderEvent(event)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        retryFailure = error
                        return@transformWhile false
                    }
                    emit(outgoing)
                    true
                }
            }
            ?.collect { emit(it) }

        retryFailure?.let { error ->
            emit(
                AgentEvent.Failed(
                    contextOverflowRecoveryFailure(ContextOverflowRecoveryStage.RETRY, error, overflow),
                ),
            )
        }
    }.catch { error ->
        // Persistence I/O (store.append inside record) is fallible and runs in this flow — including the very
        // first record(UserEntry) before the provider starts. Surface any failure as a recoverable Failed event.
        if (error is CancellationException) throw error
        emit(AgentEvent.Failed(error))
    }

    /**
     * Compact on demand (`/compact [instructions]`): summarize older turns now, regardless of the auto-compaction
     * setting or the current context size. Returns a typed unsupported/completed outcome; a completed result carries
     * the recorded [CompactionEntry], or null when there was nothing worth summarizing. Resets the tracker after work.
     */
    suspend fun compact(instructions: String? = null): CompactionResult {
        // Manual lock path: compaction suspends, while singleFlight intentionally accepts synchronous operations only.
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            if (!capabilities.clientCompaction) return CompactionResult.Unsupported
            val entry = compactor.compact(session, instructions, tokensBeforeCompaction())
            entry?.let(::recordCompaction)
            return CompactionResult.Completed(entry)
        } finally {
            turnMutex.unlock()
        }
    }

    /**
     * Prepare the session selected by process/bootstrap composition. New sessions reserve local Hosted identity only;
     * resume/continue reconnects before the frontend accepts a turn.
     */
    suspend fun activateInitialSession(resuming: Boolean, candidatePublished: Boolean = true) {
        // Manual lock path: provider/session activation may suspend while the operation remains in flight.
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            // Fresh candidates already match the provider constructed from the same configuration. Do not publish a
            // prepared binding before their one durable persistNew decision; only persisted/resumed sessions need a
            // provider holder commit here.
            val prepared = if (candidatePublished) {
                preparePromptBinding(session)
            } else {
                require(activePromptAgentName == session.promptAgentName) {
                    "Provisional PromptAgent session binding does not match its provider runtime."
                }
                null
            }
            var promptCommitted = false
            try {
                val binding = prepareBinding(session, published = candidatePublished)
                when (val lifecycle = sessionLifecycle) {
                    ProviderSessionLifecycle.None -> Unit
                    is ProviderSessionLifecycle.Hosted -> {
                        if (resuming) lifecycle.controller.activate(binding, session.entries.isNotEmpty())
                    }
                }
                promptCommitted = prepared != null
                prepared?.commit()
            } finally {
                if (prepared != null && !promptCommitted) prepared.close()
            }
        } finally {
            turnMutex.unlock()
        }
    }

    /** Start a fresh session with the current committed PromptAgent in its first published header. */
    suspend fun newSession(): Session {
        // Manual lock path: session preparation and Hosted detach may suspend before publication completes.
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            val candidate = store.newCandidate(session.cwd, context.modelName, name = null)
            val prepared = preparePromptBinding(activePromptAgentName)
            var promptCommitted = false
            try {
                candidate.promptAgentName = prepared?.agentName
                prepareBinding(candidate, published = false)
                store.persistNew(candidate)
                promptCommitted = prepared != null
                prepared?.commit()
                (sessionLifecycle as? ProviderSessionLifecycle.Hosted)?.controller?.detach()
                session = candidate
                tracker.reset()
                return candidate
            } finally {
                if (prepared != null && !promptCommitted) prepared.close()
            }
        } finally {
            turnMutex.unlock()
        }
    }

    /** Prepare an exact persisted PromptAgent (or Hosted binding) before selecting the loaded transcript. */
    suspend fun resume(id: Uuid): Session {
        // Manual lock path: resumed provider/session activation may suspend before the selected session is committed.
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            val header = store.loadHeader(id)
            val activeCwd = canonicalOrNormalized(session.cwd)
            val targetCwd = canonicalOrNormalized(header.cwd)
            require(activeCwd == targetCwd) {
                "Cannot resume session '$id' from $activeCwd because it belongs to $targetCwd. " +
                    "Launch Konductor from the session workspace."
            }
            val candidate = store.load(id)
            require(candidate.header == header) { "Session '$id' changed between header inspection and load." }
            val prepared = preparePromptBinding(candidate)
            var promptCommitted = false
            try {
                val binding = prepareBinding(candidate, published = true)
                (sessionLifecycle as? ProviderSessionLifecycle.Hosted)?.controller
                    ?.activate(binding, candidate.entries.isNotEmpty())
                promptCommitted = prepared != null
                prepared?.commit()
                session = candidate
                tracker.reset()
                return candidate
            } finally {
                if (prepared != null && !promptCommitted) prepared.close()
            }
        } finally {
            turnMutex.unlock()
        }
    }

    /** Rename the active session and persist the new label. */
    fun rename(name: String) = singleFlight { store.rename(session, name) }

    /**
     * Commit one PromptAgent name under the same single-flight boundary as turns and session changes. Persistence is
     * the sole durable decision; the prepared provider holder and live metadata are committed only after it returns.
     */
    fun bindPromptAgent(agentName: String?): PromptAgentBindingResult = singleFlight {
        val exactName = requireValidPromptAgentName(agentName)
        val management = runtime.management as? ProviderManagement.PromptAgents
            ?: throw IllegalStateException("PromptAgent management is unavailable for this provider.")
        val previousProviderName = management.binder.activeAgent
        val prepared = management.binder.prepareBinding(exactName)
        var committed = false
        try {
            val candidate = session.metadata.copy(promptAgentName = prepared.agentName)
            val changed = previousProviderName != prepared.agentName || candidate != session.metadata
            if (!changed) {
                committed = true
                prepared.commit()
                return@singleFlight PromptAgentBindingResult(prepared.agentName, changed = false)
            }
            if (candidate != session.metadata) store.persistMetadata(session, candidate)
            committed = true
            prepared.commit()
            session.commitMetadata(candidate)
            PromptAgentBindingResult(prepared.agentName, changed = true)
        } finally {
            if (!committed) prepared.close()
        }
    }

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
    fun switchModel(modelName: String): ModelSwitchResult = singleFlight {
        modelSwitchRestriction()?.let { return@singleFlight it }

        try {
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

    /** Sessions recorded for the active cwd, most-recently-updated first. */
    fun listSessions(): List<SessionSummary> = store.listForCwd(session.cwd)

    /** On-disk location of the active session, or `null` when it is not persisted. */
    fun sessionLocation(): Path? = store.locate(session)

    suspend fun close() = provider.close()

    /** Activate the exact selected binding before recording the user entry. */
    private suspend fun activateForTurn() {
        (runtime.management as? ProviderManagement.PromptAgents)?.binder?.let { binder ->
            check(binder.activeAgent == session.promptAgentName) {
                "Committed PromptAgent provider and session metadata disagree. Resume the accepted session header."
            }
        }
        val binding = prepareBinding(session, published = true)
        when (val lifecycle = sessionLifecycle) {
            ProviderSessionLifecycle.None -> Unit
            is ProviderSessionLifecycle.Hosted -> lifecycle.controller.activate(binding, session.entries.isNotEmpty())
        }
    }

    /**
     * Prepare the exact name already stored on [target] without mutating that session. Validation and the management
     * compatibility check both complete before the binder is asked to allocate a candidate.
     */
    private fun preparePromptBinding(target: Session): PreparedPromptAgentBinding? {
        val exactName = requireValidPromptAgentName(target.promptAgentName)
        val management = runtime.management as? ProviderManagement.PromptAgents
        if (management == null) {
            require(exactName == null) {
                "Session '${target.id}' requires PromptAgent management for '$exactName'."
            }
            return null
        }
        return management.binder.prepareBinding(exactName)
    }

    private fun preparePromptBinding(agentName: String?): PreparedPromptAgentBinding? {
        val exactName = requireValidPromptAgentName(agentName)
        return (runtime.management as? ProviderManagement.PromptAgents)?.binder?.prepareBinding(exactName)
    }

    /** Persist a deterministic Hosted binding before any remote create can happen. */
    private fun prepareBinding(target: Session, published: Boolean): HostedSessionBinding? =
        when (val lifecycle = sessionLifecycle) {
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
                    require(!published) {
                        "Session '${target.id}' is a Prompt v1 session and cannot be opened by a Hosted runtime. " +
                            "Start a new Hosted session."
                    }
                    require(target.entries.isEmpty()) { "A provisional Hosted session must not contain transcript entries." }
                    require(target.promptAgentName == null) {
                        "PromptAgent session '${target.id}' cannot be converted into a Hosted session."
                    }
                    HostedSessionBinding(lifecycle.controller.hostedAgentName, target.id.toString()).also {
                        target.commitMetadata(target.metadata.copy(hostedBinding = it))
                    }
                }
            }
        }
    }

    private fun canonicalOrNormalized(path: Path): Path =
        runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    private fun providerHistory(): List<Entry> = when (capabilities.sessionHistoryOwnership) {
        SessionHistoryOwnership.Client -> reconstructHistory(session.entries)
        // Hosted's server session is authoritative; its local JSONL is only an activity transcript.
        SessionHistoryOwnership.Server -> listOf(session.entries.last())
    }

    /** Fold one provider event into durable transcript/tracker state and return the exact event to expose. */
    private fun foldProviderEvent(event: AgentEvent): AgentEvent = when (event) {
        is AgentEvent.UsageReported -> {
            tracker.update(event.usage)
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

    private fun contextOverflowRecoveryFailure(
        stage: ContextOverflowRecoveryStage,
        primary: Throwable,
        originalOverflow: PromptContextOverflowException,
    ): ContextOverflowRecoveryException = ContextOverflowRecoveryException(stage, primary).also { failure ->
        if (primary !== originalOverflow) failure.addSuppressed(originalOverflow)
    }

    /**
     * Run one synchronous operation as a "single flight": at most one loop operation may be in flight, and overlap is
     * rejected instead of queued. The `try/finally` always unlocks after [operation], whether it returns (including an
     * inline non-local return) or throws. Flow-collection and suspending paths hold the same mutex manually because
     * their in-flight lifetime cannot be represented by this synchronous lambda.
     */
    private inline fun <T> singleFlight(operation: () -> T): T {
        if (!turnMutex.tryLock()) throw TurnAlreadyInProgressException()
        try {
            return operation()
        } finally {
            turnMutex.unlock()
        }
    }

    /** Persist [entry] (append-only), then append it to the active session's transcript. */
    private fun record(entry: Entry) {
        store.append(session, entry)
        session.entries += entry
    }

    /**
     * Persist a candidate transcript with the compaction marker at its `firstKeptEntryId` position, then commit that
     * exact order in memory. Both layouts become `[summarized…, marker, kept…]`, which is what [reconstructHistory]
     * slices on. A failed rewrite leaves the accepted file and live entries unchanged. The tracker is reset only after
     * both persistence and the in-memory commit succeed (the next turn re-establishes the reduced size).
     */
    private fun recordCompaction(entry: CompactionEntry) {
        val insertIndex = session.entries.indexOfFirst { it.id == entry.firstKeptEntryId }
            .let { if (it < 0) session.entries.size else it }
        val candidateEntries = session.entries.toMutableList().apply { add(insertIndex, entry) }
        store.rewrite(session, candidateEntries)
        session.entries.add(insertIndex, entry)
        tracker.reset()
    }

    private fun tokensBeforeCompaction(): Int =
        tracker.contextTokens.takeIf { it > 0 } ?: TokenEstimator.estimateTokens(session.entries)
}
