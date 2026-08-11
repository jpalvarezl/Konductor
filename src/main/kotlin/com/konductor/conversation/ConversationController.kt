package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.CompactionResult
import com.konductor.agent.PublishedSessionCommitFailure
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AssistantEntry
import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/**
 * Applies a mutation to the shared [AppState] in whatever execution context the caller requires, then makes it
 * visible — the *execute-around* (a.k.a. loan) pattern: a higher-order function that wraps a caller-supplied
 * block, the same shape as Kotlin's own `synchronized { }` / `withLock { }`
 * (see https://kotlinlang.org/docs/lambdas.html#higher-order-functions).
 *
 * [ConversationController.collectTurn] mutates [AppState] only through a [StateApplier], so it stays identical
 * whether the turn runs synchronously (the sync TUI path mutates then repaints) or on a background coroutine
 * (the async path mutates under a render lock, then repaints on its own tick). This keeps the seam free of any
 * threading or Lanterna detail — each caller supplies the context.
 */
fun interface StateApplier {
    /** Run [mutation] against [AppState] in the required context (e.g. under a render lock), then reflect it. */
    operator fun invoke(mutation: () -> Unit)
}

/**
 * The seam between the TUI and the agent loop. It translates a submitted line into an [AgentLoop] turn and
 * folds the resulting [AgentEvent]s back into the render-facing [AppState] (assistant text, token usage,
 * the working indicator, and errors). TUI slash commands are resolved through [commandRegistry]; known commands
 * never reach the model, while unknown slash-prefixed text deliberately falls through as a normal prompt.
 *
 * Streaming: assistant [AgentEvent.TextDelta]s are accumulated into a single live assistant message that is
 * upserted in place as text arrives, so the answer appears token-by-token. [submit] provides the legacy blocking
 * path; [submitAsync] runs background commands and turns off the Lanterna event loop and returns a kinded
 * [Submission]. [onUpdate]/[StateApplier] keep this class free of any Lanterna dependency.
 */
class ConversationController(
    private val state: AppState,
    private val agentLoop: AgentLoop,
    private val discoveryCommand: FoundryDiscoveryCommand,
    private val agentCommand: PromptAgentCommand? = null,
    private val strings: AppStrings = AppStrings.english(),
) {

    /** Canonical command catalog consumed by dispatch now and command discovery later. */
    val commandRegistry: CommandRegistry = CommandRegistry(
        listOf(
            FunctionalTuiCommand(BuiltInCommandDescriptors.quit) { invocation ->
                if (invocation.rawArguments.isBlank()) CommandAction.Quit else CommandAction.NotHandled
            },
            FunctionalTuiCommand(BuiltInCommandDescriptors.new) {
                CommandAction.Background(::runNew)
            },
            FunctionalTuiCommand(BuiltInCommandDescriptors.name) { invocation ->
                CommandAction.Immediate { commandName(invocation.rawArguments.trim()) }
            },
            FunctionalTuiCommand(BuiltInCommandDescriptors.session) {
                CommandAction.Immediate(::commandSession)
            },
            FunctionalTuiCommand(BuiltInCommandDescriptors.resume) { invocation ->
                val argument = invocation.rawArguments.trim()
                if (argument.isEmpty()) CommandAction.Immediate(::commandResumeList)
                else CommandAction.Background { command -> runResume(argument, command) }
            },
            FunctionalTuiCommand(
                BuiltInCommandDescriptors.compact,
                CommandAvailabilityProvider {
                    if (agentLoop.clientCompactionAvailable) {
                        CommandAvailability.Enabled
                    } else {
                        CommandAvailability.Disabled(strings.paletteCompactUnavailable)
                    }
                },
            ) { invocation ->
                CommandAction.Background { command -> runCompact(invocation.rawArguments.trim(), command) }
            },
            this.discoveryCommand.modelCommand,
            this.discoveryCommand.connectionsCommand,
            agentCommand ?: FunctionalTuiCommand(
                BuiltInCommandDescriptors.agent,
                CommandAvailabilityProvider {
                    CommandAvailability.Disabled(strings.paletteAgentUnavailable)
                },
            ) {
                CommandAction.Immediate {
                    state.addMessage(ChatMessage(MessageRole.System, strings.persistedAgentsPromptOnly))
                }
            },
        ),
    )

    /**
     * @return false when the application should stop.
     */
    fun submit(rawText: String, onUpdate: () -> Unit = {}): Boolean {
        // Trim only to detect blanks; the registry and model receive the ORIGINAL text so raw command arguments and
        // pasted prompt indentation remain intact.
        if (rawText.isBlank()) return true

        when (val action = commandRegistry.dispatch(rawText)) {
            CommandAction.Quit -> return false
            is CommandAction.Immediate -> {
                action.apply()
                onUpdate()
                return true
            }
            is CommandAction.Background -> {
                state.isAwaitingResponse = true
                onUpdate()
                val applier = StateApplier { mutation -> mutation(); onUpdate() }
                val submission = Submission.LocalCommand()
                try {
                    runBlocking {
                        submission.attach(coroutineContext.job)
                        action.run(
                            LocalCommandContext(
                                submission,
                                applier,
                                unexpectedFailure = { error -> addSystem(commandFailureText(error)) },
                            ),
                        )
                        if (submission.phase == LocalCommandPhase.Running && submission.failPreparation()) {
                            applier {
                                addSystem(commandFailureText(IllegalStateException("Command returned before commit")))
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // The blocking path has no interactive cancellation surface.
                } catch (error: Throwable) {
                    if (submission.failPreparation()) applier { addSystem(commandFailureText(error)) }
                } finally {
                    state.isAwaitingResponse = false
                    onUpdate()
                }
                return true
            }
            CommandAction.NotHandled -> Unit
        }

        state.addMessage(ChatMessage(MessageRole.User, rawText))
        state.isAwaitingResponse = true
        onUpdate()

        try {
            runBlocking { collectTurn(rawText) { block -> block(); onUpdate() } }
        } finally {
            state.isAwaitingResponse = false
            onUpdate()
        }

        return true
    }

    /** Result of [submitAsync], including the exact active identity retained through terminal reporting. */
    sealed interface Submission {
        data object Quit : Submission
        data object Handled : Submission

        sealed interface Active : Submission {
            val job: Job
            fun requestCancel(): Boolean
        }

        /**
         * Compatibility marker for callers that previously matched `Submission.Turn`. New code should distinguish
         * [AgentTurn] from [LocalCommand]; local commands deliberately do not implement this marker.
         */
        @Deprecated("Use Submission.AgentTurn for turns or Submission.Active for either active kind")
        sealed interface Turn : Active

        class AgentTurn internal constructor() : Turn {
            private val phase = AtomicReference(AgentTurnPhase.Running)
            private lateinit var launchedJob: Job
            override val job: Job get() = launchedJob

            internal fun attach(job: Job) { launchedJob = job }

            override fun requestCancel(): Boolean {
                if (!phase.compareAndSet(AgentTurnPhase.Running, AgentTurnPhase.Cancelling)) return false
                launchedJob.cancel()
                return true
            }

            internal fun observeCancellation() {
                phase.compareAndSet(AgentTurnPhase.Running, AgentTurnPhase.Cancelling)
            }

            internal fun finish(): Boolean = when {
                phase.compareAndSet(AgentTurnPhase.Running, AgentTurnPhase.Completed) -> false
                phase.compareAndSet(AgentTurnPhase.Cancelling, AgentTurnPhase.Cancelled) -> true
                else -> phase.get() == AgentTurnPhase.Cancelled
            }
        }

        class LocalCommand internal constructor() : Active {
            private val phaseRef = AtomicReference(LocalCommandPhase.Running)
            private lateinit var launchedJob: Job
            override val job: Job get() = launchedJob
            internal val phase: LocalCommandPhase get() = phaseRef.get()

            internal fun attach(job: Job) { launchedJob = job }

            override fun requestCancel(): Boolean {
                if (!phaseRef.compareAndSet(LocalCommandPhase.Running, LocalCommandPhase.Cancelling)) return false
                launchedJob.cancel()
                return true
            }

            /**
             * Admit commit only if both the command phase and its launched job still permit it. The job checks cover
             * direct child/parent cancellation that bypasses [requestCancel]: cancellation observed before the CAS
             * claims Running, while cancellation observed immediately afterward revokes that provisional admission.
             * Once the final active check succeeds, commit has won and its caller enters NonCancellable.
             */
            internal fun beginCommit(): Boolean {
                if (!launchedJob.isActive) {
                    phaseRef.compareAndSet(LocalCommandPhase.Running, LocalCommandPhase.Cancelling)
                    return false
                }
                if (!phaseRef.compareAndSet(LocalCommandPhase.Running, LocalCommandPhase.Committing)) return false
                if (launchedJob.isActive) return true

                check(phaseRef.compareAndSet(LocalCommandPhase.Committing, LocalCommandPhase.Cancelling))
                return false
            }

            internal fun finishCommit(failed: Boolean) {
                val terminal = if (failed) LocalCommandPhase.Failed else LocalCommandPhase.Completed
                check(phaseRef.compareAndSet(LocalCommandPhase.Committing, terminal))
            }

            internal fun observeCancellation() {
                phaseRef.compareAndSet(LocalCommandPhase.Running, LocalCommandPhase.Cancelling)
            }

            internal fun failPreparation(): Boolean =
                phaseRef.compareAndSet(LocalCommandPhase.Running, LocalCommandPhase.Failed)

            internal fun finishCancellation(): Boolean =
                phaseRef.compareAndSet(LocalCommandPhase.Cancelling, LocalCommandPhase.Cancelled)
        }
    }

    /**
     * Async, cancelable variant for the TUI. [onStarted] installs the exact identity before its lazy job can run.
     * [onTerminal] must verify that identity, invoke its supplied mutation, and clear the matching owner last; stale
     * callbacks therefore cannot mutate terminal copy or the shared busy flag.
     */
    fun submitAsync(rawText: String, scope: CoroutineScope, applier: StateApplier): Submission =
        submitAsync(rawText, scope, applier, onStarted = {}) { _, terminalMutation -> terminalMutation() }

    fun submitAsync(
        rawText: String,
        scope: CoroutineScope,
        applier: StateApplier,
        onStarted: (Submission.Active) -> Unit,
        onTerminal: (Submission.Active, terminalMutation: () -> Unit) -> Unit,
    ): Submission {
        if (rawText.isBlank()) return Submission.Handled

        when (val action = commandRegistry.dispatch(rawText)) {
            CommandAction.Quit -> return Submission.Quit
            is CommandAction.Immediate -> {
                action.apply()
                return Submission.Handled
            }
            is CommandAction.Background -> return launchBackground(action, scope, applier, onStarted, onTerminal)
            CommandAction.NotHandled -> Unit
        }

        // A real turn: seed the transcript before launch through the same render-lock boundary used by later folds.
        applier {
            state.addMessage(ChatMessage(MessageRole.User, rawText))
            state.isAwaitingResponse = true
        }
        val submission = Submission.AgentTurn()
        val terminalApplied = AtomicBoolean()
        fun finishTurn() {
            if (!terminalApplied.compareAndSet(false, true)) return
            val cancelled = submission.finish()
            applier {
                onTerminal(submission) {
                    if (cancelled) addSystem(strings.turnCancelled())
                    state.isAwaitingResponse = false
                }
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                collectTurn(rawText, applier)
            } catch (cancellation: CancellationException) {
                submission.observeCancellation()
                throw cancellation
            } finally {
                finishTurn()
            }
        }
        submission.attach(job)
        onStarted(submission)
        // Cancelling a lazy coroutine before start skips its body/finally, so observe and report it here.
        if (!job.start()) {
            submission.observeCancellation()
            finishTurn()
        }
        return submission
    }

    private fun launchBackground(
        action: CommandAction.Background,
        scope: CoroutineScope,
        applier: StateApplier,
        onStarted: (Submission.Active) -> Unit,
        onTerminal: (Submission.Active, terminalMutation: () -> Unit) -> Unit,
    ): Submission {
        applier { state.isAwaitingResponse = true }
        val submission = Submission.LocalCommand()
        val terminalApplied = AtomicBoolean()
        fun finishCommand() {
            if (!terminalApplied.compareAndSet(false, true)) return
            val cancelled = submission.finishCancellation()
            applier {
                onTerminal(submission) {
                    if (cancelled) addSystem(strings.commandCancelled())
                    state.isAwaitingResponse = false
                }
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action.run(
                    LocalCommandContext(
                        submission,
                        applier,
                        unexpectedFailure = { error -> addSystem(commandFailureText(error)) },
                    ),
                )
                if (submission.phase == LocalCommandPhase.Running && submission.failPreparation()) {
                    applier { addSystem(commandFailureText(IllegalStateException("Command returned before commit"))) }
                }
            } catch (cancellation: CancellationException) {
                submission.observeCancellation()
                throw cancellation
            } catch (error: Throwable) {
                if (submission.failPreparation()) applier { addSystem(commandFailureText(error)) }
            } finally {
                finishCommand()
            }
        }
        submission.attach(job)
        onStarted(submission)
        // Cancelling a lazy coroutine before start skips its body/finally, so observe and report it here.
        if (!job.start()) {
            submission.observeCancellation()
            finishCommand()
        }
        return submission
    }

    /**
     * Run one agent turn for [text] and fold its streamed [AgentEvent]s into [AppState]: assistant text is
     * accumulated into a single message upserted as deltas arrive (so it appears token-by-token), tool activity
     * and status/errors become their own system lines, usage updates the status bar, and a compaction resets the
     * token readout. Every state mutation is routed through [applier], so this method is agnostic to *how/where*
     * the mutation is applied — synchronously-then-repaint (the blocking [submit] path) or under a render lock on
     * a background coroutine (the [submitAsync] path).
     */
    private suspend fun collectTurn(text: String, applier: StateApplier) {
        val assistantText = StringBuilder()
        var assistantIndex = -1

        fun upsertAssistant(content: String) {
            if (assistantIndex < 0) {
                state.addMessage(ChatMessage(MessageRole.Assistant, content))
                assistantIndex = state.messages.lastIndex
            } else {
                state.messages[assistantIndex] = state.messages[assistantIndex].copy(content = content)
            }
        }

        // End the current assistant text burst so later output (a tool line, then the model's next burst)
        // renders as its own message *below* the tool calls instead of mutating the message above them.
        fun endAssistantBurst() {
            assistantText.setLength(0)
            assistantIndex = -1
        }

        agentLoop.runTurn(text).collect { event ->
            // Each event's state mutation goes through the [applier]: the synchronous path renders after it; the
            // async (TuiApp) path applies it under a lock while the render loop runs on the main thread.
            applier {
                when (event) {
                    is AgentEvent.TextDelta -> {
                        assistantText.append(event.text)
                        upsertAssistant(assistantText.toString())
                    }
                    is AgentEvent.Retrying -> {
                        endAssistantBurst()
                        state.addMessage(
                            ChatMessage(
                                MessageRole.System,
                                strings.retryingProvider(
                                    event.reason,
                                    event.retryAttempt,
                                    event.maxRetries,
                                    event.delayMs,
                                ),
                            ),
                        )
                    }
                    is AgentEvent.UsageReported -> state.lastUsage = event.usage
                    is AgentEvent.ToolCallStarted -> {
                        endAssistantBurst()
                        state.addMessage(ChatMessage(MessageRole.System, renderToolStartMessage(event.call, strings)))
                    }
                    is AgentEvent.ToolCallCompleted ->
                        state.addMessage(
                            ChatMessage(
                                MessageRole.System,
                                renderToolResultMessage(event.call, event.result, strings),
                            ),
                        )
                    // Reconcile to the authoritative final text (identical to the streamed deltas; also covers a
                    // turn that produced no deltas). Skip when empty so a tools-only turn adds no blank bubble.
                    is AgentEvent.TurnCompleted -> if (event.assistant.text.isNotEmpty()) upsertAssistant(event.assistant.text)
                    is AgentEvent.Failed ->
                        state.addMessage(ChatMessage(MessageRole.System, errorText(event.error)))
                    // Older turns were summarized before this turn ran. Reset the token readout so the context %
                    // visibly drops; the turn now running reports the reduced size on its next UsageReported.
                    is AgentEvent.Compacted -> {
                        endAssistantBurst()
                        state.lastUsage = null
                        state.addMessage(ChatMessage(MessageRole.System, strings.compactedForContext))
                    }
                    // Hosted-session container logs: render as their own lines (below any assistant burst).
                    is AgentEvent.LogFrame -> {
                        endAssistantBurst()
                        state.addMessage(ChatMessage(MessageRole.System, "📋 ${event.line}"))
                    }
                }
            }
        }
    }

    private suspend fun runNew(command: LocalCommandContext) {
        try {
            agentLoop.newSessionWithCommit { commitSession ->
                command.commit commitBlock@{
                    val session = try {
                        commitSession()
                    } catch (error: Throwable) {
                        val acceptedSession = (error as? PublishedSessionCommitFailure)?.acceptedSession
                        fail {
                            addSystem(
                                if (acceptedSession != null) {
                                    strings.newSessionPublishedButIncomplete(
                                        shortId(acceptedSession.id),
                                        errorReason(error),
                                    )
                                } else {
                                    strings.newSessionFailed(errorReason(error))
                                },
                            )
                        }
                        return@commitBlock
                    }
                    apply {
                        state.messages.clear()
                        state.lastUsage = null
                        state.transcriptScrollback = 0
                        state.activeAgentName = session.promptAgentName
                        addSystem(strings.newSession(shortId(session.id)))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            command.failPreparation { addSystem(strings.newSessionFailed(errorReason(error))) }
        }
    }

    private fun commandName(arg: String) {
        if (arg.isEmpty()) {
            addSystem(strings.nameUsage)
            return
        }
        agentLoop.rename(arg)
        addSystem(strings.renamedSession(arg))
    }

    private fun commandSession() {
        val session = agentLoop.session
        val location = agentLoop.sessionLocation()?.toString() ?: strings.inMemorySession
        val tokens = state.lastUsage?.let { strings.tokenCount(it.totalTokens) } ?: strings.noTokensYet
        addSystem(
            strings.sessionSummary(
                session.name ?: strings.unnamedSession,
                shortId(session.id),
                session.entries.size,
                tokens,
                location,
            ),
        )
    }

    private fun commandResumeList() {
        val sessions = agentLoop.listSessions()
        if (sessions.isEmpty()) {
            addSystem(strings.noSavedSessions)
            return
        }
        val list = sessions.mapIndexed { index, summary ->
            strings.savedSessionLine(
                index + 1,
                shortId(summary.id),
                summary.name ?: strings.unnamedSession,
                summary.entryCount,
                summary.updatedAt.toString(),
            )
        }
        addSystem(strings.savedSessionsHeader(list.joinToString("\n")))
    }

    private suspend fun runResume(arg: String, command: LocalCommandContext) {
        val sessions = agentLoop.listSessions()
        val target = arg.toIntOrNull()?.let { sessions.getOrNull(it - 1)?.id }
            ?: runCatching { Uuid.parse(arg) }.getOrNull()
        if (target == null) {
            command.commit { apply { addSystem(strings.noSuchSession(arg)) } }
            return
        }

        try {
            agentLoop.resumeWithCommit(target) { commitResume ->
                command.commit commitBlock@{
                    val loaded = try {
                        commitResume()
                    } catch (error: Throwable) {
                        fail { addSystem(strings.resumeFailed(errorReason(error))) }
                        return@commitBlock
                    }
                    apply {
                        state.messages.clear()
                        state.messages.addAll(sessionEntriesToMessages(loaded.entries, strings))
                        state.lastUsage = loaded.entries.asReversed().filterIsInstance<AssistantEntry>()
                            .firstOrNull { it.usage != null }?.usage
                        state.transcriptScrollback = 0
                        state.activeAgentName = loaded.promptAgentName
                        addSystem(strings.resumedSession(shortId(loaded.id), loaded.entries.size))
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            command.failPreparation { addSystem(strings.resumeFailed(errorReason(error))) }
        }
    }

    /** Run manual compaction with summary/candidate preparation before the atomic rewrite gate. */
    private suspend fun runCompact(instructions: String, command: LocalCommandContext) {
        try {
            agentLoop.compactWithCommit(instructions.ifBlank { null }) { commitCompaction ->
                command.commit commitBlock@{
                    val result = try {
                        commitCompaction()
                    } catch (error: Throwable) {
                        fail { addSystem(strings.compactFailed(errorReason(error))) }
                        return@commitBlock
                    }
                    apply { renderCompactionResult(result) }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            command.failPreparation { addSystem(strings.compactFailed(errorReason(error))) }
        }
    }

    private fun renderCompactionResult(result: CompactionResult) {
        when (result) {
            CompactionResult.Unsupported -> addSystem(strings.compactUnsupported)
            is CompactionResult.Completed -> if (result.entry == null) {
                addSystem(strings.compactNothing)
            } else {
                state.lastUsage = null // context % drops; the next turn re-establishes the reduced size
                addSystem(strings.compactedRecentTurns)
            }
        }
    }

    private fun addSystem(text: String) = state.addMessage(ChatMessage(MessageRole.System, text))

    private fun shortId(id: Uuid): String = id.toString().take(8)

    private fun errorText(error: Throwable): String =
        "⚠ ${errorReason(error)}"

    private fun errorReason(error: Throwable): String =
        error.message ?: error::class.simpleName ?: strings.unknownError

    private fun commandFailureText(error: Throwable): String = strings.commandFailed(errorReason(error))
}

private enum class AgentTurnPhase {
    Running,
    Cancelling,
    Cancelled,
    Completed,
}
