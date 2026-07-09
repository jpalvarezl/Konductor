package com.konductor.compaction

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceResponse
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CompactorTest {
    private val ts = Instant.parse("2026-07-09T00:00:00Z")

    // 4 chars ≈ 1 token, so a `big(100)` entry estimates to ~100 tokens.
    private fun big(tokens: Int) = "x".repeat(tokens * TokenEstimator.CHARS_PER_TOKEN)
    private fun user(text: String) = UserEntry(Uuid.random(), null, ts, text)
    private fun assistant(text: String) = AssistantEntry(Uuid.random(), null, ts, text)
    private fun toolCall(id: String) = ToolCallEntry(Uuid.random(), null, ts, ToolCall(id, "read", "{}"))
    private fun toolResult(id: String, out: String) = ToolResultEntry(Uuid.random(), null, ts, ToolResult(id, out))

    private fun session(entries: List<Entry>): Session =
        Session(Uuid.random(), null, Path.of("."), "gpt-test", ts, entries = entries.toMutableList())

    private fun compactor(vararg responses: InferenceResponse): Pair<Compactor, FakeInferenceClient> {
        val fake = FakeInferenceClient(*responses)
        // keepRecentTokens is small so the modest test transcripts have something older to summarize.
        return Compactor(PromptProvider(fake), CompactionSettings(keepRecentTokens = 150)) to fake
    }

    @Test
    fun `planCut keeps recent turns whole and summarizes older ones at a turn boundary`() {
        val u1 = user("u1"); val a1 = assistant(big(100))
        val u2 = user("u2"); val a2 = assistant(big(100))
        val u3 = user("u3"); val a3 = assistant(big(100))
        val entries = listOf(u1, a1, u2, a2, u3, a3)
        val (compactor, _) = compactor()

        val plan = compactor.planCut(entries, keepRecentTokens = 150)

        assertNotNull(plan)
        assertEquals(listOf(u1, a1), plan.toSummarize)
        assertEquals(u2.id, plan.firstKeptEntryId) // cut rounded back to the start of turn 2
    }

    @Test
    fun `planCut never splits a tool call from its result`() {
        val u1 = user("u1"); val a1 = assistant("a1")
        val u2 = user("u2"); val tc2 = toolCall("c2"); val tr2 = toolResult("c2", big(100)); val a2 = assistant("a2")
        val entries = listOf(u1, a1, u2, tc2, tr2, a2)
        val (compactor, _) = compactor()

        val plan = compactor.planCut(entries, keepRecentTokens = 50)

        assertNotNull(plan)
        // The naive token cut lands on the big tool result; it must round back to the u2 turn boundary so the
        // call+result stay together in the kept region.
        assertEquals(u2.id, plan.firstKeptEntryId)
        assertFalse(tc2 in plan.toSummarize)
        assertFalse(tr2 in plan.toSummarize)
    }

    @Test
    fun `planCut relieves a single oversized turn by cutting at its trailing assistant`() {
        // A realistic single turn: PromptProvider records only ToolCall/ToolResult entries mid-turn plus one
        // trailing AssistantEntry (the final answer). The backward token walk stops inside the tool-result region
        // BELOW that assistant, so the cut must reach UP to the trailing assistant — there is no other
        // user/assistant boundary to cut at. Without this the oversized turn could never be compacted.
        val u1 = user("u1")
        val tc1 = toolCall("c1"); val tr1 = toolResult("c1", big(100))
        val tc2 = toolCall("c2"); val tr2 = toolResult("c2", big(100))
        val a1 = assistant("final answer")
        val entries = listOf(u1, tc1, tr1, tc2, tr2, a1)
        val (compactor, _) = compactor()

        val plan = compactor.planCut(entries, keepRecentTokens = 50)

        assertNotNull(plan)
        assertEquals(a1.id, plan.firstKeptEntryId) // cut at the trailing assistant
        assertIs<AssistantEntry>(entries.first { it.id == plan.firstKeptEntryId })
        // The whole oversized span (both tool call/result pairs) is summarized; nothing is left orphaned.
        assertTrue(tr1 in plan.toSummarize && tr2 in plan.toSummarize)
        assertTrue(tc1 in plan.toSummarize && tc2 in plan.toSummarize)
    }

    @Test
    fun `summarization tolerates a stray tool call from a bound agent`() {
        val u1 = user("u1"); val a1 = assistant(big(100))
        val u2 = user("u2"); val a2 = assistant(big(100)); val u3 = user("u3"); val a3 = assistant(big(100))
        val session = session(listOf(u1, a1, u2, a2, u3, a3))
        // First summarization response emits a tool call (as a bound PromptAgent's baked tools might); the
        // no-tools executor returns a benign result instead of throwing, so the loop re-requests and the second
        // response delivers the summary — the user's turn is not failed.
        val (compactor, _) = compactor(
            InferenceResponse("", listOf(ToolCall("c1", "read", "{}")), null),
            InferenceResponse("THE SUMMARY", emptyList(), null),
        )

        val entry = runBlocking { compactor.compact(session) }

        assertNotNull(entry)
        assertEquals("THE SUMMARY", entry.summary)
    }

    @Test
    fun `planCut returns null when there is nothing older than the recent region`() {
        val (compactor, _) = compactor()
        assertNull(compactor.planCut(listOf(user("u1"), assistant("a1")), keepRecentTokens = 20_000))
    }

    @Test
    fun `compact produces a CompactionEntry carrying the model summary`() {
        val u1 = user("u1"); val a1 = assistant(big(100))
        val u2 = user("u2"); val a2 = assistant(big(100)); val u3 = user("u3"); val a3 = assistant(big(100))
        val session = session(listOf(u1, a1, u2, a2, u3, a3))
        val (compactor, _) = compactor(InferenceResponse("## Goal\nSUMMARY", emptyList(), Usage(1, 2, 3)))

        val entry = runBlocking { compactor.compact(session, tokensBefore = 42_000) }

        assertNotNull(entry)
        assertEquals("## Goal\nSUMMARY", entry.summary)
        assertEquals(u2.id, entry.firstKeptEntryId)
        assertEquals(42_000, entry.tokensBefore)
    }

    @Test
    fun `compact passes the previous summary for iterative compaction`() {
        val u2 = user("u2"); val a2 = assistant(big(100)); val u3 = user("u3"); val a3 = assistant(big(100))
        val previous = CompactionEntry(Uuid.random(), null, ts, "OLD SUMMARY", u2.id, 30_000)
        val session = session(listOf(previous, u2, a2, u3, a3))
        val (compactor, fake) = compactor(InferenceResponse("NEW SUMMARY", emptyList(), null))

        val entry = runBlocking { compactor.compact(session) }

        assertNotNull(entry)
        // The summarization request must fold in the previous summary so survivors are not lost.
        val summarizationInput = assertIs<UserEntry>(fake.requests.last().history.first())
        assertTrue(summarizationInput.text.contains("OLD SUMMARY"), "previous summary was not passed to the model")
    }

    @Test
    fun `compact returns null when there is nothing to summarize`() {
        val session = session(listOf(user("u1"), assistant("a1")))
        // No queued response: if summarization ran, the fake would throw. planCut must short-circuit first.
        val (compactor, _) = compactor()

        assertNull(runBlocking { compactor.compact(session) })
    }
}
