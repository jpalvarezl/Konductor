package com.konductor.provider.inference

import com.konductor.agent.AgentContextFactory
import com.konductor.config.Configuration
import com.konductor.core.models.UserEntry
import com.konductor.tool.BuiltinTools
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Opt-in **live** smoke test for the M2.5 persisted-PromptAgent path — skipped unless `KONDUCTOR_LIVE_TESTS` is
 * set. Requires `FOUNDRY_PROJECT_ENDPOINT` + `FOUNDRY_MODEL_NAME` + `az login`. It exercises exactly what the
 * offline tests can't (the SDK boundary): baking a real tool schema into a `PromptAgentDefinition` (which guards
 * the `BinaryData.fromObject` fix — a `fromString` schema would be rejected/invalid), binding the agent-scoped
 * Responses client, and invoking it with `instructions` omitted.
 *
 * NOTE: it leaves a `konductor-prompt-smoke` agent (a new version per run) in the project — the client seam has
 * no delete yet. Run: `$env:KONDUCTOR_LIVE_TESTS='1'; mvn -Dtest=AzureInferenceClientLiveTest test`.
 */
@EnabledIfEnvironmentVariable(named = "KONDUCTOR_LIVE_TESTS", matches = "(?i)^(1|true|yes)$")
class AzureInferenceClientLiveTest {

    @Test
    fun `creates a persisted prompt agent with tools, binds it, and invokes it`() {
        runBlocking {
            val configuration = Configuration.load()
            val client = AzureInferenceClient(configuration)
            try {
                val tools = BuiltinTools.registry(null).enabled().map { it.spec }
                val context = AgentContextFactory.build(configuration, tools = tools)

                // Baking exercises toAzurePromptTool → each tool's JSON schema must serialize as structured JSON.
                val ref = client.createAgentVersion(AGENT_NAME, context)
                assertTrue(ref.version.isNotBlank())

                client.bindAgent(ref.name)
                val response = client.respond(
                    InferenceRequest(
                        model = configuration.model,
                        systemPrompt = context.systemPrompt, // omitted under the bound agent; carried for parity
                        history = listOf(
                            UserEntry(Uuid.random(), null, Clock.System.now(), "Reply with the single word: pong."),
                        ),
                        tools = context.tools,
                        dynamicPreamble = context.dynamicPreamble,
                    ),
                )
                // A real round-trip through the persisted agent — usage is always reported on success.
                assertNotNull(response.usage)
            } finally {
                client.close()
            }
        }
    }

    private companion object {
        private const val AGENT_NAME = "konductor-prompt-smoke"
    }
}
