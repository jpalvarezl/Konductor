package com.konductor.core.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class ToolResultEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val result: ToolResult
): Entry
