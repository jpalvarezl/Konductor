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
 * Opt-in **live** smoke test for the M2.5 persisted-PromptAgent path — skipped unless KONDUCTOR_LIVE_TESTS is
 * set. Requires FOUNDRY_PROJECT_ENDPOINT + FOUNDRY_MODEL_NAME + az login. It exercises the SDK boundary the
 * offline tests cannot: the standalone [AzurePromptAgentClient] mints an agent version with real tool schemas
 * (guarding the BinaryData.fromObject baking), then [AzureInferenceClient] — bound via the agent-scoped client —
 * invokes it.
 *
 * NOTE: leaves a "konductor-prompt-smoke" agent (a new version per run) in the project; the lifecycle client has
 * no delete yet. Run: KONDUCTOR_LIVE_TESTS=1 mvn -Dtest=AzureInferenceClientLiveTest test.
 */
@EnabledIfEnvironmentVariable(named = "KONDUCTOR_LIVE_TESTS", matches = "(?i)^(1|true|yes)$")
class AzureInferenceClientLiveTest {

    @Test
    fun `creates a persisted prompt agent with tools, binds it, and invokes it`() {
        runBlocking {
            val configuration = Configuration.load()
            val tools = BuiltinTools.registry(null).enabled().map { it.spec }
            val context = AgentContextFactory.build(configuration, tools = tools)

            // Lifecycle client (separate from inference) mints the version — exercises the fromObject schema baking.
            val ref = AzurePromptAgentClient(configuration)
                .createAgentVersion(AGENT_NAME, context.modelName, context.systemPrompt, context.tools)
            assertTrue(ref.version.isNotBlank())

            // Inference bound to that agent purely by using the agent-scoped client (AzureInferenceClient unchanged).
            val inference = AzureInferenceClient(configuration, ref.name)
            try {
                val response = inference.respond(
                    InferenceRequest(
                        model = configuration.model,
                        systemPrompt = context.systemPrompt,
                        history = listOf(
                            UserEntry(Uuid.random(), null, Clock.System.now(), "Reply with the single word: pong."),
                        ),
                        tools = context.tools,
                    ),
                )
                // A real round-trip through the persisted agent — usage is always reported on success.
                assertNotNull(response.usage)
            } finally {
                inference.close()
            }
        }
    }

    private companion object {
        private const val AGENT_NAME = "konductor-prompt-smoke"
    }
}
