package com.konductor.conversation

import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.UserEntry

/**
 * Rebuild the render-facing transcript ([ChatMessage]s) from a session's [Entry] list. Used to seed the TUI
 * when a session is resumed, so the reconstructed view matches what streaming would have produced live.
 *
 * The rendering helpers ([renderToolStart]/[renderToolResult]/[compactLine]) are shared with
 * [ConversationController] so live and reconstructed transcripts format tool activity identically.
 */
fun sessionEntriesToMessages(entries: List<Entry>): List<ChatMessage> {
    val toolNames = entries.filterIsInstance<ToolCallEntry>().associate { it.call.callId to it.call.name }
    val messages = mutableListOf<ChatMessage>()
    for (entry in entries) {
        when (entry) {
            is UserEntry -> messages += ChatMessage(MessageRole.User, entry.text)
            is AssistantEntry -> if (entry.text.isNotEmpty()) {
                messages += ChatMessage(MessageRole.Assistant, entry.text)
            }
            is ToolCallEntry -> messages += ChatMessage(MessageRole.System, renderToolStart(entry.call))
            is ToolResultEntry -> messages += ChatMessage(
                MessageRole.System,
                renderToolResult(toolNames[entry.result.callId] ?: entry.result.callId, entry.result),
            )
            is CompactionEntry -> messages += ChatMessage(MessageRole.System, "— compacted: ${compactLine(entry.summary)}")
        }
    }
    return messages
}

internal fun renderToolStart(call: ToolCall): String = "⚙ ${call.name} ${compactLine(call.argumentsJson)}"

internal fun renderToolResult(name: String, result: ToolResult): String {
    val marker = if (result.isError) "✗" else "✓"
    val firstLine = result.output.lineSequence().firstOrNull().orEmpty()
    return "  $marker $name: ${compactLine(firstLine)}"
}

internal fun compactLine(text: String, max: Int = 120): String {
    val oneLine = text.replace("\n", " ").trim()
    return if (oneLine.length > max) oneLine.take(max - 1) + "…" else oneLine
}
