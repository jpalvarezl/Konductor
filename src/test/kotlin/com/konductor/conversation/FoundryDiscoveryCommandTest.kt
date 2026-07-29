package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.core.AppState
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolSpec
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.MockFoundryResponsesClient
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoundryDiscoveryCommandTest {
    private val context = AgentContext("system", emptyList(), "current-model", null)

    @Test
    fun listsModels() = runBlocking {
        val deployments = MockDeploymentCatalog(
            values = listOf(
                FoundryDeployment(
                    name = "deployment-a",
                    type = "ModelDeployment",
                    modelName = "gpt-test",
                    modelVersion = "2026-01-01",
                    modelPublisher = "OpenAI",
                ),
                FoundryDeployment(name = "application-a", type = "AppDeployment"),
            ),
        )
        val (command, state, _) = command(promptRuntime(), deployments)

        command.handle("/model list")

        val output = state.messages.single().content
        assertTrue(output.contains("deployment-a"))
        assertTrue(output.contains("gpt-test"))
        assertTrue(output.contains("2026-01-01"))
        assertTrue(output.contains("OpenAI"))
        assertTrue(!output.contains("application-a"))
    }

    @Test
    fun switchesValidModel() = runBlocking {
        val deployments = MockDeploymentCatalog(
            values = listOf(FoundryDeployment("deployment-a", "ModelDeployment")),
        )
        val (command, state, loop) = command(promptRuntime(), deployments)

        command.handle("/model deployment-a")

        assertEquals("deployment-a", loop.modelName)
        assertEquals("deployment-a", state.modelName)
        assertTrue(state.messages.single().content.contains("Switched model"))
    }

    @Test
    fun rejectsMissingModel() = runBlocking {
        val deployments = MockDeploymentCatalog(
            values = listOf(FoundryDeployment("deployment-a", "ModelDeployment")),
        )
        val (command, state, loop) = command(promptRuntime(), deployments)

        command.handle("/model missing")

        assertEquals("current-model", loop.modelName)
        assertEquals("current-model", state.modelName)
        assertTrue(state.messages.single().content.contains("No project deployment named 'missing'"))
    }

    @Test
    fun keepsModelOnOutage() = runBlocking {
        val deployments = MockDeploymentCatalog(failure = IllegalStateException("service unavailable"))
        val (command, state, loop) = command(promptRuntime(), deployments)

        command.handle("/model deployment-a")

        assertEquals("current-model", loop.modelName)
        assertEquals("current-model", state.modelName)
        assertTrue(state.messages.single().content.contains("service unavailable"))
        assertTrue(state.messages.single().content.contains("retry /model list"))
    }

    @Test
    fun cancelsBeforeSwitch() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val deployments = MockDeploymentCatalog(
            values = listOf(FoundryDeployment("deployment-a", "ModelDeployment")),
            beforeList = {
                started.countDown()
                release.await()
            },
        )
        val (command, state, loop) = command(promptRuntime(), deployments)

        val job = launch(Dispatchers.Default) { command.handle("/model deployment-a") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()

        assertEquals("current-model", loop.modelName)
        assertEquals("current-model", state.modelName)
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun listsSafeConnections() = runBlocking {
        val connections = MockConnectionCatalog(
            listOf(
                FoundryConnection(
                    name = "connection-a",
                    id = "secret-id-not-rendered",
                    type = "AZURE_OPEN_AI",
                    target = "https://example.invalid",
                    isDefault = true,
                    metadata = mapOf("apiKey" to "secret-value-not-rendered"),
                    credentialType = "API_KEY",
                ),
            ),
        )
        val (command, state, _) = command(promptRuntime(), connections = connections)

        command.handle("/connections")

        val output = state.messages.single().content
        assertTrue(output.contains("connection-a"))
        assertTrue(output.contains("AZURE_OPEN_AI"))
        assertTrue(output.contains("https://example.invalid"))
        assertTrue(output.contains("API_KEY"))
        assertTrue(!output.contains("secret-id-not-rendered"))
        assertTrue(!output.contains("secret-value-not-rendered"))
        assertTrue(!output.contains("apiKey"))
    }

    @Test
    fun handlesConnectionOutage() = runBlocking {
        val connections = MockConnectionCatalog(
            failure = IllegalStateException("secret-value-not-rendered"),
        )
        val (command, state, _) = command(promptRuntime(), connections = connections)

        command.handle("/connections")

        val output = state.messages.single().content
        assertTrue(output.contains("retry /connections"))
        assertTrue(!output.contains("secret-value-not-rendered"))
    }

    @Test
    fun gatesHostedSwitch() = runBlocking {
        val deployments = MockDeploymentCatalog(
            values = listOf(FoundryDeployment("deployment-a", "ModelDeployment")),
        )
        val (command, state, loop) = command(ProviderRuntime(MockHostedProvider), deployments)

        command.handle("/model deployment-a")

        assertEquals(0, deployments.listCalls)
        assertEquals("current-model", loop.modelName)
        assertTrue(state.messages.single().content.contains("provider owns model selection"))
    }

    @Test
    fun gatesFixedPromptAgent() = runBlocking {
        val binder = MockPromptAgentBinder("agent-a")
        val runtime = ProviderRuntime(
            PromptProvider(MockFoundryResponsesClient()),
            ProviderManagement.PromptAgents(binder, MockPromptAgentClient),
        )
        val deployments = MockDeploymentCatalog(
            values = listOf(FoundryDeployment("deployment-a", "ModelDeployment")),
        )
        val (command, state, loop) = command(runtime, deployments)

        command.handle("/model deployment-a")

        assertEquals(0, deployments.listCalls)
        assertEquals("current-model", loop.modelName)
        assertTrue(state.messages.single().content.contains("fixed by the bound agent 'agent-a'"))
    }

    private fun promptRuntime(): ProviderRuntime = ProviderRuntime(PromptProvider(MockFoundryResponsesClient()))

    private fun command(
        runtime: ProviderRuntime,
        deployments: MockDeploymentCatalog = MockDeploymentCatalog(),
        connections: MockConnectionCatalog = MockConnectionCatalog(),
    ): Triple<FoundryDiscoveryCommand, AppState, AgentLoop> {
        val state = AppState(modelName = context.modelName)
        val loop = AgentLoop(runtime, NoToolExecutor, context)
        return Triple(FoundryDiscoveryCommand(state, loop, deployments, connections), state, loop)
    }
}

private class MockDeploymentCatalog(
    private val values: List<FoundryDeployment> = emptyList(),
    private val failure: Exception? = null,
    private val beforeList: (() -> Unit)? = null,
) : FoundryDeploymentCatalog {
    var listCalls: Int = 0
        private set

    override fun listDeployments(): List<FoundryDeployment> {
        listCalls++
        beforeList?.invoke()
        failure?.let { throw it }
        return values
    }

    override fun getDeployment(name: String): FoundryDeployment = values.single { it.name == name }
}

private class MockConnectionCatalog(
    private val values: List<FoundryConnection> = emptyList(),
    private val failure: Exception? = null,
) : FoundryConnectionCatalog {
    override fun listConnections(): List<FoundryConnection> {
        failure?.let { throw it }
        return values
    }
    override fun getConnection(name: String): FoundryConnection = values.single { it.name == name }
}

private object MockHostedProvider : AgentProvider {
    override val kind: AgentKind = AgentKind.Hosted
    override val capabilities: ProviderCapabilities = ProviderCapabilities.Hosted
    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = emptyFlow()
    override suspend fun close() = Unit
}

private class MockPromptAgentBinder(
    initialAgent: String?,
) : PromptAgentBinder {
    override var activeAgent: String? = initialAgent
        private set

    override fun bindAgent(agentName: String?) {
        activeAgent = agentName
    }
}

private object MockPromptAgentClient : PromptAgentClient {
    override suspend fun listAgents(): List<String> = emptyList()

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef = PromptAgentRef(name, "1")
}
