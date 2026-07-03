package com.konductor.conversation

import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole

class ConversationController(
    private val state: AppState,
) {
    /**
     * @return false when the application should stop.
     */
    fun submit(rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return true

        if (text.equals("/quit", ignoreCase = true) || text.equals("/exit", ignoreCase = true)) {
            return false
        }

        state.addMessage(ChatMessage(MessageRole.User, text))

        // Placeholder behavior while the app is only a TUI shell. Swap this seam with an agent/session service later.
        state.addMessage(
            ChatMessage(
                MessageRole.Assistant,
                "Echo: $text\n\nWire ConversationController into your real backend when you're ready.",
            ),
        )

        return true
    }
}
