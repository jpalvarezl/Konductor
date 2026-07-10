package com.konductor.provider

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.Usage

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent           // streamed assistant text
    data class Status(val message: String) : AgentEvent           // brief retry/provider status
    data class ToolCallStarted(val call: ToolCall) : AgentEvent
    data class ToolCallCompleted(val call: ToolCall, val result: ToolResult) : AgentEvent
    data class LogFrame(val line: String) : AgentEvent            // Hosted session logs
    data class UsageReported(val usage: Usage) : AgentEvent
    data class Compacted(val entry: CompactionEntry) : AgentEvent // older turns summarized before this turn
    data class TurnCompleted(val assistant: AssistantEntry) : AgentEvent
    data class Failed(val error: Throwable) : AgentEvent
}
