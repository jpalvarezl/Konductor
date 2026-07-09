package com.konductor.compaction

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.UserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TokenEstimatorTest {
    private val ts = Instant.parse("2026-07-09T00:00:00Z")

    @Test
    fun `estimateTokens is chars over four rounded up`() {
        assertEquals(0, TokenEstimator.estimateTokens(""))
        assertEquals(1, TokenEstimator.estimateTokens("a"))
        assertEquals(1, TokenEstimator.estimateTokens("abcd"))
        assertEquals(2, TokenEstimator.estimateTokens("abcde"))
    }

    @Test
    fun `serializeEntry labels each role`() {
        assertEquals("[User]: hello", TokenEstimator.serializeEntry(UserEntry(Uuid.random(), null, ts, "hello")))
        assertEquals(
            "[Assistant]: hi",
            TokenEstimator.serializeEntry(AssistantEntry(Uuid.random(), null, ts, "hi")),
        )
        assertEquals(
            "[Assistant tool calls]: read({\"path\":\"C.kt\"})",
            TokenEstimator.serializeEntry(
                ToolCallEntry(Uuid.random(), null, ts, ToolCall("c1", "read", "{\"path\":\"C.kt\"}")),
            ),
        )
    }

    @Test
    fun `tool results are truncated with a dropped-char marker`() {
        val big = "x".repeat(TokenEstimator.TOOL_RESULT_MAX_CHARS + 500)
        val entry = ToolResultEntry(Uuid.random(), null, ts, ToolResult("c1", big))

        val text = TokenEstimator.serializeEntry(entry)

        assertTrue(text.startsWith("[Tool result]: "))
        assertTrue(text.contains("+500 chars truncated"), "expected the dropped-char marker in: $text")
        assertTrue(text.length < big.length)
    }

    @Test
    fun `short tool results are kept verbatim`() {
        val entry = ToolResultEntry(Uuid.random(), null, ts, ToolResult("c1", "short output"))
        assertEquals("[Tool result]: short output", TokenEstimator.serializeEntry(entry))
    }

    @Test
    fun `assistant tool calls serialize on the same line`() {
        val entry = AssistantEntry(
            Uuid.random(),
            null,
            ts,
            "I will read it",
            toolCalls = listOf(ToolCall("c1", "read", "{\"path\":\"C.kt\"}")),
        )

        val text = TokenEstimator.serializeEntry(entry)

        assertFalse("\n" in text, "assistant tool call serialization must stay on one line: $text")
        assertTrue(text.contains("[Assistant tool calls]:"))
    }

    @Test
    fun `serializeSpan joins entries one per line`() {
        val span = listOf(
            UserEntry(Uuid.random(), null, ts, "u"),
            AssistantEntry(Uuid.random(), null, ts, "a"),
        )
        assertEquals("[User]: u\n[Assistant]: a", TokenEstimator.serializeSpan(span))
    }
}
