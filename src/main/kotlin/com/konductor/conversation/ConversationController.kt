package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AssistantEntry
import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * the working indicator, and errors). Session slash-commands (`/new`, `/name`, `/session`, `/resume`) are
 * handled locally against the [AgentLoop]'s [SessionStore][com.konductor.session.SessionStore] and never
 * reach the model.
 *
 * Streaming: assistant [AgentEvent.TextDelta]s are accumulated into a single live assistant message that is
 * upserted in place as text arrives, so the answer appears token-by-token. [submit] provides the legacy blocking
 * path; [submitAsync] runs the turn off the Lanterna event loop and returns a [Submission], with
 * [Submission.Turn] carrying the cancelable [Job] used by `Esc`. [onUpdate]/[StateApplier] keep this class free
 * of any Lanterna dependency.
 */
class ConversationController(
    private val state: AppState,
    private val agentLoop: AgentLoop,
    private val agentCommand: PromptAgentCommand? = null,
    private val strings: AppStrings = AppStrings.english(),
) {
    /**
     * @return false when the application should stop.
     */
    fun submit(rawText: String, onUpdate: () -> Unit = {}): Boolean {
        // Trim only to detect blanks and slash-commands; the model and transcript get the ORIGINAL text so
        // pasted snippets keep their leading/trailing whitespace and indentation.
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return true

        if (trimmed.equals("/quit", ignoreCase = true) || trimmed.equals("/exit", ignoreCase = true)) {
            return false
        }

        // /agent manages the opt-in persisted PromptAgent binding (M2.5); handled before the turn path so it
        // never reaches the model. Delegated to a dedicated handler to keep this seam small.
        if (isAgentCommand(trimmed)) {
            if (agentCommand != null) {
                agentCommand.handle(trimmed)
            } else {
                state.addMessage(ChatMessage(MessageRole.System, strings.persistedAgentsPromptOnly))
            }
            onUpdate()
            return true
        }

        // Recognized session commands are handled locally; any other `/...` line falls through to the model
        // (so a path like `/etc/hosts` still reaches it).
        if (trimmed.startsWith("/") && handleCommand(trimmed, onUpdate)) {
            onUpdate()
            return true
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

    /** Result of [submitAsync]: the app should quit, a command was handled, or a cancelable turn was started. */
    sealed interface Submission {
        data object Quit : Submission
        data object Handled : Submission
        data class Turn(val job: Job) : Submission
    }

    /**
     * Async, cancelable variant of [submit] for the TUI's non-blocking event loop. Blank input, `/quit`, `/agent`,
     * and session slash-commands are applied synchronously on the caller's (event-loop) thread; a real turn is
     * launched in [scope] and returned as [Submission.Turn] whose [Job] the caller can cancel (Esc). The turn's
     * state mutations are applied through [applier] (e.g. under a render lock, since they run off the event-loop
     * thread); the render loop repaints the streamed output on its own tick.
     */
    fun submitAsync(rawText: String, scope: CoroutineScope, applier: StateApplier): Submission {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return Submission.Handled

        if (trimmed.equals("/quit", ignoreCase = true) || trimmed.equals("/exit", ignoreCase = true)) {
            return Submission.Quit
        }
        if (isAgentCommand(trimmed)) {
            if (agentCommand != null) {
                agentCommand.handle(trimmed)
            } else {
                state.addMessage(ChatMessage(MessageRole.System, strings.persistedAgentsPromptOnly))
            }
            return Submission.Handled
        }
        // /compact runs a summarization inference call, so — like a turn — launch it on `scope` and drive UI
        // through the `applier`. Running it synchronously here would block the event-loop thread (no working
        // indicator, no Esc). It's returned as a cancelable Turn.
        compactInstructions(trimmed)?.let { return launchCompactAsync(it, scope, applier) }
        if (trimmed.startsWith("/") && handleCommand(trimmed) {}) {
            return Submission.Handled
        }

        // A real turn: seed the transcript on the event-loop thread (no turn is running yet), then launch the
        // cancelable turn. collectTurn applies every subsequent mutation through the [applier].
        state.addMessage(ChatMessage(MessageRole.User, rawText))
        state.isAwaitingResponse = true
        val job = scope.launch {
            try {
                collectTurn(rawText, applier)
            } finally {
                applier { state.isAwaitingResponse = false }
            }
        }
        return Submission.Turn(job)
    }

    /** If [input] is the `/compact [instructions]` command, return its (possibly empty) instructions; else null. */
    private fun compactInstructions(input: String): String? {
        val parts = input.split(Regex("\\s+"), limit = 2)
        return if (parts[0].lowercase() == "/compact") parts.getOrNull(1)?.trim().orEmpty() else null
    }

    private fun launchCompactAsync(instructions: String, scope: CoroutineScope, applier: StateApplier): Submission {
        // No turn is running yet, so seed on the caller's (event-loop) thread; all mutations from the launched
        // coroutine go through the `applier`. Returned as a Turn so Esc can cancel the summarization.
        state.isAwaitingResponse = true
        val job = scope.launch {
            try {
                val entry = agentLoop.compact(instructions.ifBlank { null })
                applier {
                    if (entry == null) {
                        addSystem(strings.compactNothing)
                    } else {
                        state.lastUsage = null // context % drops; the next turn re-establishes the reduced size
                        addSystem(strings.compactedRecentTurns)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                applier { addSystem(strings.compactFailed(errorReason(error))) }
            } finally {
                applier { state.isAwaitingResponse = false }
            }
        }
        return Submission.Turn(job)
    }

    // Match "/agent" in any case, followed by end-of-line or any whitespace, so `/Agent`, `/agent\tlist`, etc.
    // are all intercepted here rather than leaking to the model.
    private fun isAgentCommand(text: String): Boolean {
        val cmd = "/agent"
        return text.equals(cmd, ignoreCase = true) ||
            (text.length > cmd.length &&
                text.regionMatches(0, cmd, 0, cmd.length, ignoreCase = true) &&
                text[cmd.length].isWhitespace())
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

    /** @return true when [input] was a recognized session command (already handled). */
    private fun handleCommand(input: String, onUpdate: () -> Unit): Boolean {
        val parts = input.split(Regex("\\s+"), limit = 2)
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        return when (parts[0].lowercase()) {
            "/new" -> { commandNew(); true }
            "/name" -> { commandName(arg); true }
            "/session" -> { commandSession(); true }
            "/resume" -> { commandResume(arg); true }
            "/compact" -> { commandCompact(arg, onUpdate); true }
            "/model" -> { commandModel(arg); true }
            else -> false
        }
    }

    private fun commandNew() {
        val session = agentLoop.newSession()
        agentCommand?.onFreshSession() // a new session keeps (and records) the currently-bound agent
        state.messages.clear()
        state.lastUsage = null
        state.transcriptScrollback = 0
        addSystem(strings.newSession(shortId(session.id)))
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

    private fun commandModel(arg: String) {
        if (arg.isEmpty()) {
            addSystem(strings.activeModel(agentLoop.modelName))
            return
        }
        // A bound persisted PromptAgent supplies its own baked-in model; the agent-scoped request never sends a
        // model, so switching here would silently no-op. Reject it rather than report a switch that won't happen.
        state.activeAgentName?.let { agent ->
            addSystem(strings.modelFixedByAgent(agent))
            return
        }
        val target = arg.trim()
        val previous = agentLoop.modelName
        val result = runCatching { agentLoop.switchModel(target) }
        result.onSuccess {
            state.modelName = agentLoop.modelName
            state.lastUsage = null
            addSystem(strings.modelSwitched(previous, agentLoop.modelName))
        }.onFailure {
            addSystem(strings.modelSwitchFailed(errorReason(it)))
        }
    }

    private fun commandResume(arg: String) {
        val sessions = agentLoop.listSessions()
        if (arg.isEmpty()) {
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
            return
        }

        val target = arg.toIntOrNull()?.let { sessions.getOrNull(it - 1)?.id }
            ?: runCatching { Uuid.parse(arg) }.getOrNull()
        if (target == null) {
            addSystem(strings.noSuchSession(arg))
            return
        }

        val loaded = runCatching { agentLoop.resume(target) }.getOrElse {
            addSystem(strings.resumeFailed(errorReason(it)))
            return
        }
        state.messages.clear()
        state.messages.addAll(sessionEntriesToMessages(loaded.entries, strings))
        state.lastUsage = loaded.entries.asReversed().filterIsInstance<AssistantEntry>()
            .firstOrNull { it.usage != null }?.usage
        state.transcriptScrollback = 0
        addSystem(strings.resumedSession(shortId(loaded.id), loaded.entries.size))
        // Restore the session's persisted agent (validated + volatility fallback), or unbind if it was ephemeral.
        agentCommand?.onResumedSession(loaded.promptAgentName)
    }

    /**
     * Compact the transcript on demand (`/compact [instructions]`): summarize older turns now. Runs a
     * summarization inference call synchronously (like a turn), so it shows the working indicator. Optional
     * free-text [instructions] focus the summary.
     */
    private fun commandCompact(instructions: String, onUpdate: () -> Unit) {
        state.isAwaitingResponse = true
        onUpdate()
        val result = runCatching { runBlocking { agentLoop.compact(instructions.ifBlank { null }) } }
        state.isAwaitingResponse = false
        result.onSuccess { entry ->
            if (entry == null) {
                addSystem(strings.compactNothing)
            } else {
                state.lastUsage = null // context % drops; the next turn re-establishes the reduced size
                addSystem(strings.compactedRecentTurns)
            }
        }.onFailure { addSystem(strings.compactFailed(errorReason(it))) }
    }

    private fun addSystem(text: String) = state.addMessage(ChatMessage(MessageRole.System, text))

    private fun shortId(id: Uuid): String = id.toString().take(8)

    private fun errorText(error: Throwable): String =
        "⚠ ${errorReason(error)}"

    private fun errorReason(error: Throwable): String =
        error.message ?: error::class.simpleName ?: strings.unknownError
}
