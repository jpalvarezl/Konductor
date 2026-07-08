package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AssistantEntry
import com.konductor.provider.AgentEvent
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * The seam between the TUI and the agent loop. It translates a submitted line into an [AgentLoop] turn and
 * folds the resulting [AgentEvent]s back into the render-facing [AppState] (assistant text, token usage,
 * the working indicator, and errors). Session slash-commands (`/new`, `/name`, `/session`, `/resume`) are
 * handled locally against the [AgentLoop]'s [SessionStore][com.konductor.session.SessionStore] and never
 * reach the model.
 *
 * Streaming: assistant [AgentEvent.TextDelta]s are accumulated into a single live assistant message that is
 * upserted in place as text arrives, so the answer appears token-by-token. The turn runs synchronously
 * (`runBlocking`) — the Lanterna event loop blocks until it finishes, but [onUpdate] repaints between deltas.
 * Non-blocking input + `Esc` cancellation are a later refinement. [onUpdate] keeps this class free of any
 * Lanterna dependency.
 */
class ConversationController(
    private val state: AppState,
    private val agentLoop: AgentLoop,
    private val agentCommand: PromptAgentCommand? = null,
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
                val message = "Persisted agents are available only on the Prompt provider."
                state.addMessage(ChatMessage(MessageRole.System, message))
            }
            onUpdate()
            return true
        }

        // Recognized session commands are handled locally; any other `/...` line falls through to the model
        // (so a path like `/etc/hosts` still reaches it).
        if (trimmed.startsWith("/") && handleCommand(trimmed)) {
            onUpdate()
            return true
        }

        state.addMessage(ChatMessage(MessageRole.User, rawText))
        state.isAwaitingResponse = true
        onUpdate()

        try {
            runBlocking { collectTurn(rawText, onUpdate) }
        } finally {
            state.isAwaitingResponse = false
            onUpdate()
        }

        return true
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

    private suspend fun collectTurn(text: String, onUpdate: () -> Unit) {
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
            when (event) {
                is AgentEvent.TextDelta -> {
                    assistantText.append(event.text)
                    upsertAssistant(assistantText.toString())
                }
                is AgentEvent.UsageReported -> state.lastUsage = event.usage
                is AgentEvent.ToolCallStarted -> {
                    endAssistantBurst()
                    state.addMessage(ChatMessage(MessageRole.System, renderToolStart(event.call)))
                }
                is AgentEvent.ToolCallCompleted ->
                    state.addMessage(ChatMessage(MessageRole.System, renderToolResult(event.call.name, event.result)))
                // Reconcile to the authoritative final text (identical to the streamed deltas; also covers a
                // turn that produced no deltas). Skip when empty so a tools-only turn adds no blank bubble.
                is AgentEvent.TurnCompleted -> if (event.assistant.text.isNotEmpty()) upsertAssistant(event.assistant.text)
                is AgentEvent.Failed ->
                    state.addMessage(ChatMessage(MessageRole.System, errorText(event.error)))
                // Hosted-session container logs: render as their own lines (below any assistant burst).
                is AgentEvent.LogFrame -> {
                    endAssistantBurst()
                    state.addMessage(ChatMessage(MessageRole.System, "📋 ${event.line}"))
                }
            }
            onUpdate()
        }
    }

    /** @return true when [input] was a recognized session command (already handled). */
    private fun handleCommand(input: String): Boolean {
        val parts = input.split(Regex("\\s+"), limit = 2)
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        return when (parts[0].lowercase()) {
            "/new" -> { commandNew(); true }
            "/name" -> { commandName(arg); true }
            "/session" -> { commandSession(); true }
            "/resume" -> { commandResume(arg); true }
            else -> false
        }
    }

    private fun commandNew() {
        val session = agentLoop.newSession()
        agentCommand?.onFreshSession() // a new session keeps (and records) the currently-bound agent
        state.messages.clear()
        state.lastUsage = null
        state.transcriptScrollback = 0
        addSystem("Started a new session (${shortId(session.id)}).")
    }

    private fun commandName(arg: String) {
        if (arg.isEmpty()) {
            addSystem("Usage: /name <label>")
            return
        }
        agentLoop.rename(arg)
        addSystem("Renamed session to \"$arg\".")
    }

    private fun commandSession() {
        val session = agentLoop.session
        val location = agentLoop.sessionLocation()?.toString() ?: "(in-memory — not persisted)"
        val tokens = state.lastUsage?.let { "${it.totalTokens} tokens" } ?: "no tokens yet"
        addSystem(
            "Session ${session.name ?: "(unnamed)"} • id ${shortId(session.id)} • " +
                "${session.entries.size} entries • $tokens\n$location",
        )
    }

    private fun commandResume(arg: String) {
        val sessions = agentLoop.listSessions()
        if (arg.isEmpty()) {
            if (sessions.isEmpty()) {
                addSystem("No saved sessions for this directory.")
                return
            }
            val list = sessions.mapIndexed { index, summary ->
                "  ${index + 1}. ${shortId(summary.id)}  ${summary.name ?: "(unnamed)"}  " +
                    "${summary.entryCount} entries  ${summary.updatedAt}"
            }
            addSystem("Saved sessions (use /resume <number|id>):\n" + list.joinToString("\n"))
            return
        }

        val target = arg.toIntOrNull()?.let { sessions.getOrNull(it - 1)?.id }
            ?: runCatching { Uuid.parse(arg) }.getOrNull()
        if (target == null) {
            addSystem("No such session '$arg'. Run /resume to list saved sessions.")
            return
        }

        val loaded = runCatching { agentLoop.resume(target) }.getOrElse {
            addSystem("Could not resume session: ${it.message ?: it::class.simpleName}")
            return
        }
        state.messages.clear()
        state.messages.addAll(sessionEntriesToMessages(loaded.entries))
        state.lastUsage = loaded.entries.asReversed().filterIsInstance<AssistantEntry>()
            .firstOrNull { it.usage != null }?.usage
        state.transcriptScrollback = 0
        addSystem("Resumed session ${shortId(loaded.id)} (${loaded.entries.size} entries).")
        // Restore the session's persisted agent (validated + volatility fallback), or unbind if it was ephemeral.
        agentCommand?.onResumedSession(loaded.promptAgentName)
    }

    private fun addSystem(text: String) = state.addMessage(ChatMessage(MessageRole.System, text))

    private fun shortId(id: Uuid): String = id.toString().take(8)

    private fun errorText(error: Throwable): String =
        "⚠ ${error.message ?: error::class.simpleName ?: "unknown error"}"
}
