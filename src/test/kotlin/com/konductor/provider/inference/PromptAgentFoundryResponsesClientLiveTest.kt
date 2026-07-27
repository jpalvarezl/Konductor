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
 * Opt-in **live** smoke test for the M2.5 persisted-PromptAgent path - skipped unless KONDUCTOR_LIVE_TESTS is set.
 * Requires FOUNDRY_PROJECT_ENDPOINT + FOUNDRY_MODEL_NAME + az login. The lifecycle client mints an agent version
 * with real tool schemas (guarding fromObject baking) from the STABLE base prompt, then
 * PromptAgentFoundryResponsesClient invokes it (agent-scoped client + input-only payload + dynamic-preamble item).
 *
 * NOTE: leaves a "konductor-prompt-smoke" agent (a new version per run) in the project.
 * Run: KONDUCTOR_LIVE_TESTS=1 mvn -Dtest=PromptAgentFoundryResponsesClientLiveTest test.
 */
@EnabledIfEnvironmentVariable(named = "KONDUCTOR_LIVE_TESTS", matches = "(?i)^(1|true|yes)$")
class PromptAgentFoundryResponsesClientLiveTest {

    @Test
    fun `creates a persisted prompt agent, binds it, and invokes it`() {
        runBlocking {
            val cfg = Configuration.load()
            val tools = BuiltinTools.registry(null).enabled().map { it.spec }
            val context = AgentContextFactory.build(cfg, tools = tools)

            val ref = AzurePromptAgentClient(cfg)
                .createAgentVersion(AGENT_NAME, context.modelName, context.baseSystemPrompt, context.tools)
            assertTrue(ref.version.isNotBlank())
            println("LIVE created ${ref.name} v${ref.version}")

            val responsesClient = PromptAgentFoundryResponsesClient(cfg, ref.name)
            try {
                val result = responsesClient.respond(
                    FoundryResponsesRequest(
                        model = cfg.model,
                        systemPrompt = context.systemPrompt,
                        history = listOf(
                            UserEntry(Uuid.random(), null, Clock.System.now(), "Reply with the single word: pong."),
                        ),
                        tools = context.tools,
                        dynamicPreamble = context.dynamicPreamble,
                    ),
                )
                println("LIVE response text='${result.text}' tokens=${result.usage?.totalTokens}")
                assertNotNull(result.usage)
                assertTrue(result.text.isNotBlank() || result.toolCalls.isNotEmpty())
            } finally {
                responsesClient.close()
            }
        }
    }

    private companion object {
        private const val AGENT_NAME = "konductor-prompt-smoke"
    }
}
