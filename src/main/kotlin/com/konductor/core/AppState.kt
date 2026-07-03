package com.konductor.core

class AppState(
    initialMessages: List<ChatMessage> = emptyList(),
) {
    val messages: MutableList<ChatMessage> = initialMessages.toMutableList()
    val input: InputState = InputState()

    /**
     * Number of rendered transcript lines above the bottom. A value of zero means the transcript is pinned to newest
     * output, while larger values represent scrolling up into history.
     */
    var transcriptScrollback: Int = 0

    fun addMessage(message: ChatMessage) {
        messages += message
        transcriptScrollback = 0
    }
}
