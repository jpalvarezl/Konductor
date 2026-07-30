package com.konductor.provider.inference

import com.konductor.core.models.UserEntry
import com.konductor.foundry.FoundryTestBase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Unary ephemeral Prompt Responses coverage in PLAYBACK (default), RECORD, or LIVE mode. */
class EphemeralFoundryResponsesClientTest : FoundryTestBase() {
    @Test
    fun responds() {
        runBlocking {
            val client = EphemeralFoundryResponsesClient(ephemeralOpenAIClient())
            try {
                val result = client.respond(
                    FoundryResponsesRequest(
                        model = configuredEphemeralModelName(),
                        systemPrompt = "Return only the requested synthetic ASCII text, preserving every hyphen.",
                        history = listOf(
                            UserEntry(
                                Uuid.parse("00000000-0000-0000-0000-000000000097"),
                                null,
                                Instant.parse("2026-01-01T00:00:00Z"),
                                "Reply with exactly this text and nothing else: ephemeral-prompt-ok",
                            ),
                        ),
                        tools = emptyList(),
                    ),
                )

                assertEquals("ephemeral-prompt-ok", result.text)
                assertTrue(result.toolCalls.isEmpty())
                val usage = assertNotNull(result.usage)
                assertTrue(usage.inputTokens > 0)
                assertTrue(usage.outputTokens > 0)
                assertEquals(usage.inputTokens + usage.outputTokens, usage.totalTokens)
            } finally {
                client.close()
            }
        }
    }
}
