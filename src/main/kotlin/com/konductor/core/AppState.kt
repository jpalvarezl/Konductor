package com.konductor.core

import com.konductor.core.models.Usage

class AppState(
    initialMessages: List<ChatMessage> = emptyList(),
    /** Deployment/model name shown in the status bar. */
    modelName: String? = null,
    /** Configured context window for context-percent display. */
    val contextWindowTokens: Int = 128_000,
    activeAgentName: String? = null,
) {
    val messages: MutableList<ChatMessage> = initialMessages.toMutableList()
    val input: InputState = InputState()

    /** Deployment/model name used for subsequent turns and shown in the status bar. */
    var modelName: String? = modelName

    /**
     * The persisted PromptAgent bound to this session (M2.5), or null for the ephemeral path. Shown in the
     * status bar.
     */
    var activeAgentName: String? = activeAgentName

    /**
     * Number of rendered transcript lines above the bottom. A value of zero means the transcript is pinned to newest
     * output, while larger values represent scrolling up into history.
     */
    var transcriptScrollback: Int = 0

    /** Latest token usage reported by the model, rendered in the status bar. Null until the first turn completes. */
    var lastUsage: Usage? = null

    /** True while a turn is in flight, so the UI can show a working indicator. */
    var isAwaitingResponse: Boolean = false

    fun addMessage(message: ChatMessage) {
        messages += message
        transcriptScrollback = 0
    }
}
