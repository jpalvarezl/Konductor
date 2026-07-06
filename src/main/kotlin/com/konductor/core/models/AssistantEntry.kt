package com.konductor.core.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class AssistantEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage : Usage? = null
) : Entry
