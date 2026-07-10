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
import com.konductor.i18n.AppStrings

/**
 * Rebuild the render-facing transcript ([ChatMessage]s) from a session's [Entry] list. Used to seed the TUI
 * when a session is resumed, so the reconstructed view matches what streaming would have produced live.
 *
 * The rendering helpers ([renderToolStart]/[renderToolResult]/[compactLine]) are shared with
 * [ConversationController] so live and reconstructed transcripts format tool activity identically.
 */
fun sessionEntriesToMessages(
    entries: List<Entry>,
    strings: AppStrings = AppStrings.english(),
): List<ChatMessage> {
    val toolCalls = entries.filterIsInstance<ToolCallEntry>().associate { it.call.callId to it.call }
    val messages = mutableListOf<ChatMessage>()
    for (entry in entries) {
        when (entry) {
            is UserEntry -> messages += ChatMessage(MessageRole.User, entry.text)
            is AssistantEntry -> if (entry.text.isNotEmpty()) {
                messages += ChatMessage(MessageRole.Assistant, entry.text)
            }
            is ToolCallEntry -> messages += ChatMessage(MessageRole.System, renderToolStartMessage(entry.call, strings))
            is ToolResultEntry -> messages += ChatMessage(
                MessageRole.System,
                renderToolResultMessage(toolCalls[entry.result.callId], entry.result, strings),
            )
            is CompactionEntry ->
                messages += ChatMessage(MessageRole.System, strings.compactedTranscript(compactLine(entry.summary)))
        }
    }
    return messages
}

internal fun renderToolStartMessage(
    call: ToolCall,
    strings: AppStrings = AppStrings.english(),
): String = "⚙ ${renderToolCall(call, strings).summary}"

internal fun renderToolResultMessage(
    call: ToolCall?,
    result: ToolResult,
    strings: AppStrings = AppStrings.english(),
): String {
    val marker = if (result.isError) "✗" else "✓"
    val rendered = if (call != null) {
        com.konductor.conversation.renderToolResult(call, result, strings)
    } else {
        com.konductor.conversation.renderToolResult(result.callId, result, strings)
    }
    return "  $marker ${rendered.summary}"
}

internal fun compactLine(text: String, max: Int = 120): String {
    val oneLine = text.replace("\n", " ").trim()
    return if (oneLine.length > max) oneLine.take(max - 1) + "…" else oneLine
}
