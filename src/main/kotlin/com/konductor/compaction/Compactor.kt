package com.konductor.compaction

import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.ToolResult
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Summarizes older transcript turns into a [CompactionEntry] so the Prompt provider can keep going as the
 * context window fills (docs/spec/compaction.md). The **trigger** lives in the agent loop
 * ([ContextWindowTracker]); this class does the cut planning + summarization.
 *
 * Summarization reuses the loop's [AgentProvider] as a one-shot **no-tools** turn: the serialized span rides a
 * single user message, so it works both for the ephemeral path (where the summary template is also the system
 * prompt) and under a bound M2.5 PromptAgent (which ignores request `instructions`, so the template must live in
 * the user text). The summarization turn is consumed here and is **never** persisted to the session.
 */
class Compactor(
    private val provider: AgentProvider,
    private val settings: CompactionSettings,
) {
    /** The plan for one compaction: which entries to summarize, and the id of the first entry to keep. */
    data class CutPlan(val toSummarize: List<Entry>, val firstKeptEntryId: Uuid)

    /**
     * Summarize the older span of [session] and return a [CompactionEntry]. Returns null when there is nothing
     * worth compacting (too little history, or it is all recent). [instructions] optionally focuses the summary;
     * [tokensBefore] records the pre-compaction context size for display (defaults to a local estimate).
     *
     * The caller (agent loop) is responsible for appending/persisting the returned entry.
     */
    suspend fun compact(
        session: Session,
        instructions: String? = null,
        tokensBefore: Int = TokenEstimator.estimateTokens(session.entries),
    ): CompactionEntry? {
        val plan = planCut(session.entries, settings.keepRecentTokens) ?: return null
        val previousSummary = session.entries.filterIsInstance<CompactionEntry>().lastOrNull()?.summary
        val summary = summarize(
            serialized = TokenEstimator.serializeSpan(plan.toSummarize),
            previousSummary = previousSummary,
            instructions = instructions,
            modelName = session.modelName,
        )
        if (summary.isBlank()) return null
        return CompactionEntry(
            id = Uuid.random(),
            // The marker is inserted before the first kept entry, so in the linear chain its parent is the last
            // summarized entry (docs/spec/sessions.md — parentId is informational until branching lands).
            parentId = plan.toSummarize.lastOrNull()?.id,
            timestamp = Clock.System.now(),
            summary = summary,
            firstKeptEntryId = plan.firstKeptEntryId,
            tokensBefore = tokensBefore,
        )
    }

    /**
     * Plan the cut: walk backward summing token estimates until [keepRecentTokens] is reached, then round the
     * boundary to a valid cut point. Returns null when nothing older than the recent region remains.
     *
     * Cut points are restricted to user/assistant messages, which guarantees a tool call and its result are
     * never split (they sit between an assistant turn and the next user turn). A turn boundary (user message) is
     * preferred; a single turn larger than [keepRecentTokens] (a "split turn") falls back to cutting at an
     * assistant message mid-turn.
     */
    fun planCut(entries: List<Entry>, keepRecentTokens: Int): CutPlan? {
        val regionStart = regionStart(entries)
        if (regionStart >= entries.size) return null

        // Walk backward from the newest entry, accumulating estimated tokens, until we have kept enough.
        var kept = 0
        var index = entries.size
        while (index > regionStart) {
            val candidate = index - 1
            kept += TokenEstimator.estimateTokens(entries[candidate])
            index = candidate
            if (kept >= keepRecentTokens) break
        }

        // If the whole un-summarized region fits within keepRecentTokens, there is nothing worth compacting.
        // (This also gates the split-turn upward fallback in roundToCut to genuinely oversized regions, so a
        // short transcript never gets needlessly summarized.)
        if (kept < keepRecentTokens) return null

        val cutIndex = roundToCut(entries, index, regionStart) ?: return null
        if (cutIndex <= regionStart) return null // nothing older than the recent region to summarize

        return CutPlan(
            toSummarize = entries.subList(regionStart, cutIndex).toList(),
            firstKeptEntryId = entries[cutIndex].id,
        )
    }

    /**
     * Find the first index of the un-summarized region: everything up to (and including) the last
     * [CompactionEntry] has already been summarized, so a new pass starts at that compaction's `firstKeptEntryId`
     * (re-summarizing the survivors, whose content the previous summary is passed alongside). Mirrors the slicing
     * in `com.konductor.session.reconstructHistory`.
     */
    private fun regionStart(entries: List<Entry>): Int {
        val lastCompaction = entries.indexOfLast { it is CompactionEntry }
        if (lastCompaction < 0) return 0
        val located = entries.indexOfFirst { it.id == (entries[lastCompaction] as CompactionEntry).firstKeptEntryId }
        return (if (located > lastCompaction) located else lastCompaction + 1).coerceAtMost(entries.size)
    }

    /**
     * Round [from] to a valid cut index above [regionStart]. Preference order: (1) a turn boundary (user message)
     * at or before [from] — keeps recent whole turns; (2) an assistant message at or before [from] — a split-turn
     * cut inside the recent region; (3) the nearest user/assistant boundary *above* [from].
     *
     * Case (3) is what handles a **single oversized turn**: a real turn is laid out `[User, ToolCall, ToolResult,
     * …, Assistant]` with its only assistant at the very end, so the backward token walk stops inside the
     * tool-result region *below* that assistant — cases (1)/(2) then find nothing. Cutting at the trailing
     * assistant (case 3) summarizes the oversized span and keeps a small tail, rather than giving up (which would
     * leave the transcript un-compactable and eventually overflow the model). Returns null only when the region
     * has no user/assistant message at all.
     */
    private fun roundToCut(entries: List<Entry>, from: Int, regionStart: Int): Int? {
        val upper = from.coerceAtMost(entries.size - 1)
        for (i in upper downTo regionStart + 1) {
            if (entries[i] is UserEntry) return i
        }
        for (i in upper downTo regionStart + 1) {
            if (entries[i] is AssistantEntry) return i
        }
        for (i in from + 1 until entries.size) {
            if (entries[i] is UserEntry || entries[i] is AssistantEntry) return i
        }
        return null
    }

    /** Run the summarization as a no-tools provider turn and return the assistant's text. */
    private suspend fun summarize(
        serialized: String,
        previousSummary: String?,
        instructions: String?,
        modelName: String,
    ): String {
        val request = TurnRequest(
            context = AgentContext(
                systemPrompt = SUMMARY_SYSTEM_PROMPT,
                tools = emptyList(),
                modelName = modelName,
            ),
            history = listOf(
                UserEntry(
                    id = Uuid.random(),
                    parentId = null,
                    timestamp = Clock.System.now(),
                    text = buildSummaryRequest(serialized, previousSummary, instructions),
                ),
            ),
        )
        val streamed = StringBuilder()
        var finalText: String? = null
        provider.runTurn(request, NO_TOOLS).collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> streamed.append(event.text)
                is AgentEvent.TurnCompleted -> finalText = event.assistant.text
                is AgentEvent.Failed -> throw event.error
                else -> Unit
            }
        }
        return (finalText?.ifBlank { null } ?: streamed.toString()).trim()
    }

    private fun buildSummaryRequest(serialized: String, previousSummary: String?, instructions: String?): String =
        buildString {
            append(SUMMARY_SYSTEM_PROMPT).append("\n\n")
            if (!previousSummary.isNullOrBlank()) {
                append("Existing summary of even older turns — fold its still-relevant content into your output:\n")
                append(previousSummary).append("\n\n")
            }
            if (!instructions.isNullOrBlank()) {
                append("Extra focus for this summary: ").append(instructions).append("\n\n")
            }
            append("Conversation transcript to summarize:\n").append(serialized).append("\n\n")
            append("Produce the summary using EXACTLY this markdown template (drop empty sections):\n")
            append(SUMMARY_TEMPLATE)
        }

    private companion object {
        // The summarization context advertises no tools, but a bound PromptAgent has server-side baked tools that
        // can't be removed. If the model emits a stray tool call, return a benign error result (rather than
        // throwing) so the summarization turn still converges instead of failing the user's turn.
        private val NO_TOOLS = ToolExecutor { call ->
            ToolResult(
                callId = call.callId,
                output = "No tools are available during compaction; summarize from the provided text only.",
                isError = true,
            )
        }

        private val SUMMARY_SYSTEM_PROMPT = """
            You are a compaction assistant for a terminal coding agent. Summarize the provided transcript into a
            concise, structured handoff so the agent can continue coherently from the recent (un-summarized)
            turns. Capture goals, decisions, progress, and any file paths that were read or modified. Output ONLY
            the summary in the requested markdown template — do not continue the conversation or address the user.
        """.trimIndent()

        private val SUMMARY_TEMPLATE = """
            ## Goal
            [What the user is trying to accomplish]

            ## Constraints & Preferences
            - [Requirements the user stated]

            ## Progress
            ### Done
            - [x] ...
            ### In Progress
            - [ ] ...
            ### Blocked
            - ...

            ## Key Decisions
            - **[Decision]**: [rationale]

            ## Next Steps
            1. ...

            ## Critical Context
            - [Data needed to continue]

            <read-files>
            path/to/file.kt
            </read-files>
            <modified-files>
            path/to/changed.kt
            </modified-files>
        """.trimIndent()
    }
}
