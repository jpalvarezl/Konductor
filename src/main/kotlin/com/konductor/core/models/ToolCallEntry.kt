package com.konductor.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@SerialName("tool_call")
data class ToolCallEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val call: ToolCall,
) : Entry
