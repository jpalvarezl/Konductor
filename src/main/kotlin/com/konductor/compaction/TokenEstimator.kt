package com.konductor.compaction

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.UserEntry

/**
 * Plain-text serialization + token estimation for compaction (docs/spec/compaction.md#serialization--truncation).
 *
 * Two jobs:
 *  - **Estimate** an entry span's token cost so the cut point (which older entries to summarize) can be planned
 *    without a tokenizer. A coarse chars/[CHARS_PER_TOKEN] heuristic is enough — the *trigger* uses the model's
 *    authoritative `Usage.totalTokens`; estimates only decide the boundary.
 *  - **Serialize** a span to flat text so the model treats it as material to summarize, not a conversation to
 *    continue. Tool results (which dominate token cost) are truncated to [TOOL_RESULT_MAX_CHARS] with a marker.
 */
object TokenEstimator {
    const val CHARS_PER_TOKEN: Int = 4

    /** Tool results are the heavy tail; truncate them harder here than the per-call cap in tools.md. */
    const val TOOL_RESULT_MAX_CHARS: Int = 2_000

    /** Coarse token estimate for a string: `ceil(length / CHARS_PER_TOKEN)`. Empty text costs 0. */
    fun estimateTokens(text: String): Int = (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** Token estimate for a single entry, based on its serialized (truncated) form. */
    fun estimateTokens(entry: Entry): Int = estimateTokens(serializeEntry(entry))

    /** Token estimate for a span of entries. */
    fun estimateTokens(entries: List<Entry>): Int = entries.sumOf { estimateTokens(it) }

    /** Serialize a span to the flat `[Role]: ...` transcript the summarizer consumes. */
    fun serializeSpan(entries: List<Entry>): String =
        entries.joinToString("\n") { serializeEntry(it) }

    /** One entry as a single labeled line (tool output truncated). */
    fun serializeEntry(entry: Entry): String = when (entry) {
        is UserEntry -> "[User]: ${entry.text}"
        is AssistantEntry -> buildString {
            append("[Assistant]: ").append(entry.text)
            if (entry.toolCalls.isNotEmpty()) {
                append("\n[Assistant tool calls]: ")
                append(entry.toolCalls.joinToString("; ") { "${it.name}(${it.argumentsJson})" })
            }
        }
        is ToolCallEntry -> "[Assistant tool calls]: ${entry.call.name}(${entry.call.argumentsJson})"
        is ToolResultEntry -> "[Tool result]: ${truncate(entry.result.output)}"
        is CompactionEntry -> "[Summary]: ${entry.summary}"
    }

    private fun truncate(output: String): String {
        if (output.length <= TOOL_RESULT_MAX_CHARS) return output
        val dropped = output.length - TOOL_RESULT_MAX_CHARS
        return output.take(TOOL_RESULT_MAX_CHARS) + " … [+$dropped chars truncated]"
    }
}
