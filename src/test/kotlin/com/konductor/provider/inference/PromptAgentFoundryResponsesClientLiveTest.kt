package com.konductor.provider.inference

import com.konductor.agent.AgentContextFactory
import com.konductor.config.Configuration
import com.konductor.core.models.UserEntry
import com.konductor.foundry.project.FoundryProjectRuntime
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.tool.BuiltinTools
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import com.azure.core.test.annotation.LiveOnly
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * **Live-only** smoke test for the M2.5 persisted-PromptAgent path.
 * Requires FOUNDRY_PROJECT_ENDPOINT + FOUNDRY_MODEL_NAME + az login. The lifecycle client mints an agent version
 * with real tool schemas (guarding fromObject baking) from the STABLE base prompt, then
 * PromptAgentFoundryResponsesClient invokes it (agent-scoped client + input-only payload + dynamic-preamble item).
 *
 * NOTE: leaves a "konductor-prompt-smoke" agent (a new version per run) in the project.
 * Run: AZURE_TEST_MODE=LIVE ./mvnw -Dtest=PromptAgentFoundryResponsesClientLiveTest test.
 */
@LiveOnly
class PromptAgentFoundryResponsesClientLiveTest {

    @Test
    fun `creates a persisted prompt agent, binds it, and invokes it`() {
        runBlocking {
            val cfg = Configuration.load(agentKindOverride = AgentKind.Prompt).copy(promptAgentName = null)
            val tools = BuiltinTools.registry(null).enabled().map { it.spec }
            val context = AgentContextFactory.build(cfg, tools = tools)

            val project = FoundryProjectRuntime.create(cfg)
            val runtime = project.createProvider(cfg)
            try {
                val management = assertIs<ProviderManagement.PromptAgents>(runtime.management)
                val ref = management.lifecycle.createAgentVersion(
                    AGENT_NAME,
                    context.modelName,
                    context.baseSystemPrompt,
                    context.tools,
                )
                assertTrue(ref.version.isNotBlank())
                println("LIVE created ${ref.name} v${ref.version}")
                management.binder.bindAgent(ref.name)

                val events = runtime.provider.runTurn(
                    TurnRequest(
                        context,
                        listOf(UserEntry(Uuid.random(), null, Clock.System.now(), "Reply with: pong.")),
                    ),
                    ToolExecutor { error("The live smoke prompt must not call a tool.") },
                ).toList()
                val completed = assertIs<AgentEvent.TurnCompleted>(events.last())
                println("LIVE response text='${completed.assistant.text}'")
                assertTrue(completed.assistant.text.isNotBlank())
            } finally {
                runtime.close()
            }
        }
    }

    private companion object {
        private const val AGENT_NAME = "konductor-prompt-smoke"
    }
}
