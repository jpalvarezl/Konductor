package com.konductor.tui

import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.conversation.BuiltInCommandDescriptors
import com.konductor.conversation.ConversationController
import com.konductor.conversation.FoundryDiscoveryCommand
import com.konductor.core.AppState
import com.konductor.core.CommandPaletteMode
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolSpec
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import com.konductor.session.SessionSummary
import com.konductor.tui.palette.CommandPaletteController
import com.konductor.tui.palette.PaletteAction
import com.konductor.tui.palette.PaletteKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PaletteOptionSourcesTest {
    @Test
    fun `production composition wires model and resume from canonical descriptors`() = runBlocking {
        val sessionId = Uuid.parse("00000000-0000-0000-0000-000000000090")
        var sessionLoads = 0

        val sources = composePaletteOptionSources(
            EmptyDeploymentCatalog,
            listSessions = {
                sessionLoads++
                listOf(
                    SessionSummary(
                        id = sessionId,
                        name = "Review session",
                        createdAt = Instant.parse("2026-07-29T10:00:00Z"),
                        updatedAt = Instant.parse("2026-07-30T10:00:00Z"),
                        entryCount = 4,
                    ),
                )
            },
            providerManagement = ProviderManagement.None,
        )

        val model = assertNotNull(sources[BuiltInCommandDescriptors.model.name])
        assertEquals(BuiltInCommandDescriptors.model.insertionPrefix, model.insertionPrefix)
        val resume = assertNotNull(sources[BuiltInCommandDescriptors.resume.name])
        assertEquals(BuiltInCommandDescriptors.resume.insertionPrefix, resume.insertionPrefix)
        assertEquals(listOf(sessionId.toString()), resume.provider.loadOptions().map { it.value })
        assertEquals(1, sessionLoads)
    }

    @Test
    fun `production composition wires managed PromptAgents to the intentional nested command`() = runBlocking {
        val management = RecordingPromptAgentManagement(listOf("billing", "Agent With Spaces"))

        val sources = composePaletteOptionSources(
            EmptyDeploymentCatalog,
            listSessions = { emptyList() },
            providerManagement = ProviderManagement.PromptAgents(management, management),
        )

        val agent = assertNotNull(sources[BuiltInCommandDescriptors.agent.name])
        assertEquals("/agent use ", agent.insertionPrefix)
        assertEquals(
            listOf("billing", "Agent With Spaces"),
            agent.provider.loadOptions().map { it.value },
        )
        assertEquals(1, management.listCalls)
    }

    @Test
    fun `unmanaged Prompt and Hosted production compositions keep the agent command disabled`() {
        val runtimes = listOf(
            ProviderRuntime(PaletteRuntimeProvider(AgentKind.Prompt, ProviderCapabilities.prompt(false))),
            ProviderRuntime(PaletteRuntimeProvider(AgentKind.Hosted, ProviderCapabilities.Hosted)),
        )

        runtimes.forEach { runtime ->
            val (state, palette) = productionPalette(runtime)
            palette.open(state)

            val agentItem = assertNotNull(state.commandPalette?.allItems?.single {
                it.id == BuiltInCommandDescriptors.agent.name
            })
            assertFalse(agentItem.enabled)
            assertEquals("Available only on the Prompt provider.", agentItem.disabledReason)
            "agent".forEach { palette.handle(state, PaletteKey.Character(it)) }
            assertEquals(BuiltInCommandDescriptors.agent.name, state.commandPalette?.selectedItem?.id)
            assertIs<PaletteAction.None>(palette.handle(state, PaletteKey.Enter))
            assertEquals(CommandPaletteMode.Commands, state.commandPalette?.mode)
        }
    }

    private fun productionPalette(runtime: ProviderRuntime): Pair<AppState, CommandPaletteController> {
        val state = AppState(modelName = "gpt-test")
        val loop = AgentLoop(
            runtime,
            NoToolExecutor,
            AgentContext("system", emptyList(), "gpt-test", null),
        )
        val conversation = ConversationController(
            state,
            loop,
            FoundryDiscoveryCommand(state, loop, EmptyDeploymentCatalog, EmptyConnectionCatalog),
        )
        val sources = composePaletteOptionSources(
            EmptyDeploymentCatalog,
            listSessions = { emptyList() },
            providerManagement = runtime.management,
        )
        return state to CommandPaletteController(conversation.commandRegistry, optionSources = sources)
    }
}

private object EmptyDeploymentCatalog : FoundryDeploymentCatalog {
    override fun listDeployments(): List<FoundryDeployment> = emptyList()

    override fun getDeployment(name: String): FoundryDeployment = error("Not used by palette composition")
}

private object EmptyConnectionCatalog : FoundryConnectionCatalog {
    override fun listConnections(): List<FoundryConnection> = emptyList()

    override fun getConnection(name: String): FoundryConnection = error("Not used by palette composition")
}

private class RecordingPromptAgentManagement(
    private val agents: List<String>,
) : PromptAgentBinder, PromptAgentClient {
    override var activeAgent: String? = "billing"
    var listCalls: Int = 0
        private set

    override fun prepareBinding(agentName: String?) =
        com.konductor.core.models.requireValidPromptAgentName(agentName).let { exactName ->
            com.konductor.provider.inference.PreparedPromptAgentBinding(exactName, commitAction = {
                activeAgent = exactName
            })
        }

    override suspend fun listAgents(): List<String> {
        listCalls++
        return agents
    }

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef = error("Not used by palette composition")
}

private class PaletteRuntimeProvider(
    override val kind: AgentKind,
    override val capabilities: ProviderCapabilities,
) : AgentProvider {
    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = emptyFlow()

    override suspend fun close() = Unit
}
