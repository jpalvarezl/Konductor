package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.core.AppState
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolSpec
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.deployment.FoundryDeployment
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoundryDiscoveryCommandTest {
    private val context = AgentContext("system", emptyList(), "current-model", null)

    private suspend fun execute(command: TuiCommand, input: String) {
        val invocation = requireNotNull(CommandInvocation.parse(input))
        val submission = ConversationController.Submission.LocalCommand().also {
            it.attach(kotlin.coroutines.coroutineContext.job)
        }
        val context = LocalCommandContext(
            submission,
            StateApplier { it() },
            unexpectedFailure = { throw it },
        )
        assertIs<CommandAction.Background>(command.execute(invocation)).run(context)
    }

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

        execute(command.modelCommand, "/model list")

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

        execute(command.modelCommand, "/model deployment-a")

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

        execute(command.modelCommand, "/model missing")

        assertEquals("current-model", loop.modelName)
        assertEquals("current-model", state.modelName)
        assertTrue(state.messages.single().content.contains("No project deployment named 'missing'"))
    }

    @Test
    fun switchesWithoutValidationOnOutage() = runBlocking {
        val deployments = MockDeploymentCatalog(failure = IllegalStateException("service unavailable"))
        val (command, state, loop) = command(promptRuntime(), deployments)

        execute(command.modelCommand, "/model deployment-a")

        assertEquals("deployment-a", loop.modelName)
        assertEquals("deployment-a", state.modelName)
        assertTrue(state.messages.single().content.contains("without Foundry validation"))
        assertTrue(state.messages.single().content.contains("service unavailable"))
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

        val job = launch(Dispatchers.Default) { execute(command.modelCommand, "/model deployment-a") }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()

        assertEquals("current-model", loop.modelName)
        assertEquals("current-model", state.modelName)
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun cancellationImmediatelyBeforeCommitWinsTheSharedCommandGate() = runBlocking {
        val deployments = MockDeploymentCatalog(
            values = listOf(FoundryDeployment("deployment-a", "ModelDeployment")),
        )
        val (command, state, loop) = command(promptRuntime(), deployments)
        val action = assertIs<CommandAction.Background>(
            command.modelCommand.execute(requireNotNull(CommandInvocation.parse("/model deployment-a"))),
        )
        val submission = ConversationController.Submission.LocalCommand().also { it.attach(Job()) }
        val context = LocalCommandContext(
            submission,
            StateApplier { it() },
            unexpectedFailure = { throw it },
            beforeBeginCommit = { submission.requestCancel() },
        )

        val failure = runCatching { action.run(context) }.exceptionOrNull()

        assertIs<CancellationException>(failure)
        assertEquals(LocalCommandPhase.Cancelling, submission.phase)
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

        execute(command.connectionsCommand, "/connections")

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

        execute(command.connectionsCommand, "/connections")

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

        val availability = requireNotNull(command.modelCommand.availabilityProvider).availability()
        val reason = assertIs<CommandAvailability.Disabled>(availability).reason
        assertTrue(reason.contains("provider owns model selection"))
        execute(command.modelCommand, "/model deployment-a")

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

        val availability = requireNotNull(command.modelCommand.availabilityProvider).availability()
        assertTrue(assertIs<CommandAvailability.Disabled>(availability).reason.contains("agent 'agent-a'"))
        execute(command.modelCommand, "/model deployment-a")

        assertEquals(0, deployments.listCalls)
        assertEquals("current-model", loop.modelName)
        assertTrue(state.messages.single().content.contains("fixed by the bound agent 'agent-a'"))
    }

    @Test
    fun reportsUnavailableComposition() = runBlocking {
        val (available, availableState) = command(promptRuntime())
        execute(available.modelCommand, "/model list")
        execute(available.connectionsCommand, "/connections")

        val state = AppState(modelName = context.modelName)
        val loop = AgentLoop(promptRuntime(), NoToolExecutor, context)
        val unavailable = FoundryDiscoveryCommand.unavailable(state, loop)
        execute(unavailable.modelCommand, "/model list")
        execute(unavailable.connectionsCommand, "/connections")

        assertTrue(availableState.messages[0].content.startsWith("No model deployments"))
        assertTrue(availableState.messages[1].content.startsWith("No connections"))
        assertTrue(state.messages.all { it.content.contains("unavailable in this application") })
        assertTrue(state.messages.none { it.content.startsWith("No model deployments") })
        assertTrue(state.messages.none { it.content.startsWith("No connections") })
    }

    @Test
    fun switchesWhenDiscoveryUnavailable() = runBlocking {
        val state = AppState(modelName = context.modelName)
        val loop = AgentLoop(promptRuntime(), NoToolExecutor, context)
        val command = FoundryDiscoveryCommand.unavailable(state, loop)

        execute(command.modelCommand, "/model deployment-a")

        assertEquals("deployment-a", loop.modelName)
        assertEquals("deployment-a", state.modelName)
        assertTrue(state.messages.single().content.contains("project discovery is unavailable"))
        assertTrue(state.messages.single().content.contains("next model request is authoritative"))
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

private typealias MockDeploymentCatalog = MockFoundryDeploymentCatalog
private typealias MockConnectionCatalog = MockFoundryConnectionCatalog

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

    override fun prepareBinding(agentName: String?) =
        com.konductor.core.models.requireValidPromptAgentName(agentName).let { exactName ->
            com.konductor.provider.inference.PreparedPromptAgentBinding(exactName, commitAction = {
                activeAgent = exactName
            })
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
