package com.konductor.provider.inference

import com.konductor.core.models.ToolSpec
import com.konductor.core.models.UserEntry
import com.konductor.foundry.FoundryTestBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Persisted PromptAgent Responses smoke test in PLAYBACK (default), RECORD, or LIVE mode. */
class PromptAgentFoundryResponsesClientTest : FoundryTestBase() {
    @Test
    fun createAndInvokeAgent() {
        runBlocking {
            val agentName = configuredPromptAgentName()
            val modelName = configuredPromptAgentModelName()
            val agentsClient = promptAgentsClient()
            val ref = AzurePromptAgentClient(agentsClient).createAgentVersion(
                agentName,
                modelName,
                "You are a deterministic PromptAgent test agent.",
                listOf(representativeTool()),
            )

            try {
                val responsesClient = PromptAgentFoundryResponsesClient(promptAgentOpenAIClient(ref.name))
                try {
                    val result = responsesClient.respond(
                        FoundryResponsesRequest(
                            model = modelName,
                            systemPrompt = "The persisted agent owns these instructions.",
                            history = listOf(
                                UserEntry(
                                    Uuid.parse("00000000-0000-0000-0000-000000000071"),
                                    null,
                                    Instant.parse("2026-01-01T00:00:00Z"),
                                    "Reply with the single word: pong.",
                                ),
                            ),
                            tools = emptyList(),
                            dynamicPreamble = "Deterministic PromptAgent test context.",
                        ),
                    )

                    assertEquals(agentName, ref.name)
                    assertTrue(ref.version.isNotBlank())
                    assertTrue(result.text.isNotBlank())
                    assertNotNull(result.usage)
                } finally {
                    responsesClient.close()
                }
            } finally {
                agentsClient.deleteAgent(ref.name)
            }
        }
    }

    private fun representativeTool(): ToolSpec = ToolSpec(
        name = "lookup_weather",
        description = "Look up a test city's weather.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") { put("type", "string") }
            }
            putJsonArray("required") { add("city") }
        },
    )
}
