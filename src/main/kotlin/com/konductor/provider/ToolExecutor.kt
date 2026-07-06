package com.konductor.provider

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult

fun interface ToolExecutor {
    suspend fun execute(call: ToolCall): ToolResult
}
