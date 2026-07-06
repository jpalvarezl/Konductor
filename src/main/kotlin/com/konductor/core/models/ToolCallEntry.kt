package com.konductor.core.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class ToolCallEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val call: ToolCall
    ): Entry
