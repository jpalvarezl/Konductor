package com.konductor.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@SerialName("assistant")
data class AssistantEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null
) : Entry
