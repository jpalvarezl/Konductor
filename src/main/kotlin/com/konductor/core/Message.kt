package com.konductor.core

import java.time.Instant

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val createdAt: Instant = Instant.now(),
)

enum class MessageRole {
    User,
    Assistant,
    System,
}
