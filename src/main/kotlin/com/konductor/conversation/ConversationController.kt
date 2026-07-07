package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.provider.AgentEvent
import kotlinx.coroutines.runBlocking

/**
 * The seam between the TUI and the agent loop. It translates a submitted line into an [AgentLoop] turn and
 * folds the resulting [AgentEvent]s back into the render-facing [AppState] (assistant text, token usage,
 * the working indicator, and errors).
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
) {
    /**
     * @return false when the application should stop.
     */
    fun submit(rawText: String, onUpdate: () -> Unit = {}): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return true

        if (text.equals("/quit", ignoreCase = true) || text.equals("/exit", ignoreCase = true)) {
            return false
        }

        state.addMessage(ChatMessage(MessageRole.User, text))
        state.isAwaitingResponse = true
        onUpdate()

        try {
            runBlocking { collectTurn(text, onUpdate) }
        } finally {
            state.isAwaitingResponse = false
            onUpdate()
        }

        return true
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

        agentLoop.runTurn(text).collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> {
                    assistantText.append(event.text)
                    upsertAssistant(assistantText.toString())
                }
                is AgentEvent.UsageReported -> state.lastUsage = event.usage
                // Reconcile to the authoritative final text (identical to the streamed deltas; also covers a
                // turn that produced no deltas).
                is AgentEvent.TurnCompleted -> upsertAssistant(event.assistant.text)
                is AgentEvent.Failed ->
                    state.addMessage(ChatMessage(MessageRole.System, errorText(event.error)))
                // Deferred rendering: tool calls (M2), hosted logs (M5).
                is AgentEvent.ToolCallStarted,
                is AgentEvent.ToolCallCompleted,
                is AgentEvent.LogFrame,
                -> Unit
            }
            onUpdate()
        }
    }

    private fun errorText(error: Throwable): String =
        "⚠ ${error.message ?: error::class.simpleName ?: "unknown error"}"
}
