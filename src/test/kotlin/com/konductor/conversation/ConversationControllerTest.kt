package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import com.konductor.core.models.Usage
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.FoundryResponsesEvent
import com.konductor.provider.inference.FoundryResponsesClient
import com.konductor.provider.inference.FoundryResponsesRequest
import com.konductor.provider.inference.FoundryResponsesResult
import com.konductor.provider.inference.MockFoundryResponsesClient
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class ConversationControllerTest {
    private val context = AgentContext(
        systemPrompt = "sys",
        tools = emptyList(),
        modelName = "gpt-test",
        temperature = null,
    )

    private fun controllerWith(vararg responses: FoundryResponsesResult): Pair<ConversationController, AppState> =
        controllerWith(NoToolExecutor, *responses)

    private fun controllerWith(
        toolExecutor: ToolExecutor,
        vararg responses: FoundryResponsesResult,
    ): Pair<ConversationController, AppState> {
        val state = AppState()
        val loop = AgentLoop(PromptProvider(MockFoundryResponsesClient(*responses)), toolExecutor, context)
        return controller(state, loop) to state
    }

    private fun controller(
        state: AppState,
        loop: AgentLoop,
        agentCommand: PromptAgentCommand? = null,
        strings: AppStrings = AppStrings.english(),
        discoveryCommand: FoundryDiscoveryCommand = mockFoundryDiscovery(state, loop, strings = strings),
    ): ConversationController = ConversationController(
        state,
        loop,
        discoveryCommand,
        agentCommand,
        strings,
    )

    private fun availabilityMatrix(controller: ConversationController): Map<String, Boolean> =
        controller.commandRegistry.entries()
            .filter { it.descriptor.name in setOf("/compact", "/model", "/agent") }
            .associate { it.descriptor.name to (it.availability is CommandAvailability.Enabled) }

    @Test
    fun `blank input is ignored and keeps the app running`() {
        val (controller, state) = controllerWith()

        val shouldContinue = controller.submit("   ")

        assertTrue(shouldContinue)
        assertEquals(emptyList(), state.messages)
    }

    @Test
    fun registersEveryTopLevelCommandOnce() {
        val (controller, _) = controllerWith()

        assertEquals(
            listOf(
                "/quit",
                "/new",
                "/name",
                "/session",
                "/resume",
                "/compact",
                "/model",
                "/connections",
                "/agent",
            ),
            controller.commandRegistry.descriptors.map(CommandDescriptor::name),
        )
        assertEquals(setOf("/exit"), controller.commandRegistry.descriptors.first().aliases)
    }

    @Test
    fun `provider availability matrix reflects runtime capabilities`() {
        val (plainPrompt, _) = controllerWith()

        val binder = MockBindingFoundryResponsesClient().also { it.bindAgent("agent-a") }
        val managedRuntime = ProviderRuntime(
            PromptProvider(binder),
            ProviderManagement.PromptAgents(binder, MockControllerPromptAgentClient),
        )
        val managedState = AppState(modelName = context.modelName, activeAgentName = "agent-a")
        val managedLoop = AgentLoop(managedRuntime, NoToolExecutor, context)
        val managedAgent = PromptAgentCommand(
            managedState,
            { context },
            binder,
            MockControllerPromptAgentClient,
            recordAgent = {},
        )
        val managedPrompt = controller(managedState, managedLoop, managedAgent)

        val hostedProvider = object : AgentProvider {
            override val kind = AgentKind.Hosted
            override val capabilities = ProviderCapabilities.Hosted
            override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = emptyFlow()
            override suspend fun close() = Unit
        }
        val hostedState = AppState(modelName = context.modelName)
        val hostedLoop = AgentLoop(hostedProvider, NoToolExecutor, context)
        val hosted = controller(hostedState, hostedLoop)

        assertEquals(
            mapOf("/compact" to true, "/model" to true, "/agent" to false),
            availabilityMatrix(plainPrompt),
        )
        assertEquals(
            mapOf("/compact" to true, "/model" to false, "/agent" to true),
            availabilityMatrix(managedPrompt),
        )
        assertEquals(
            mapOf("/compact" to false, "/model" to false, "/agent" to false),
            availabilityMatrix(hosted),
        )
        assertEquals(
            "Available only on the Prompt provider.",
            assertIs<CommandAvailability.Disabled>(
                plainPrompt.commandRegistry.entries().single { it.descriptor.name == "/agent" }.availability,
            ).reason,
        )
        assertEquals(
            "Available only on the Prompt provider.",
            assertIs<CommandAvailability.Disabled>(
                hosted.commandRegistry.entries().single { it.descriptor.name == "/agent" }.availability,
            ).reason,
        )
    }

    @Test
    fun `quit commands stop the app without adding messages`() {
        val (controller, state) = controllerWith()

        assertFalse(controller.submit("/quit"))
        assertFalse(controller.submit("/EXIT"))
        assertEquals(emptyList(), state.messages)
    }

    @Test
    fun exactOnlyArgumentsFallThrough() {
        val (controller, state) = controllerWith(
            FoundryResponsesResult("quit answer", emptyList(), null),
            FoundryResponsesResult("connections answer", emptyList(), null),
        )

        assertTrue(controller.submit("/quit now"))
        assertTrue(controller.submit("/connections details"))

        assertEquals(listOf("/quit now", "/connections details"), state.messages.filter {
            it.role == MessageRole.User
        }.map(ChatMessage::content))
    }

    @Test
    fun pathSlashFallsThrough() = runBlocking {
        val (controller, state) = controllerWith(
            FoundryResponsesResult("hosts answer", emptyList(), null),
            FoundryResponsesResult("bin answer", emptyList(), null),
        )

        assertTrue(controller.submit("/etc/hosts"))
        val submission = assertIs<ConversationController.Submission.Turn>(
            controller.submitAsync("/usr/bin", this) { it() },
        )
        submission.job.join()

        assertEquals(listOf("/etc/hosts", "/usr/bin"), state.messages.filter {
            it.role == MessageRole.User
        }.map(ChatMessage::content))
    }

    @Test
    fun `submitted text preserves whitespace and renders the model answer and token usage`() {
        val usage = Usage(inputTokens = 7, outputTokens = 3, totalTokens = 10)
        val (controller, state) = controllerWith(
            FoundryResponsesResult(text = "Hi back", toolCalls = emptyList(), usage = usage),
        )

        val shouldContinue = controller.submit("  hello  ")

        assertTrue(shouldContinue)
        assertEquals(2, state.messages.size)
        assertEquals(MessageRole.User, state.messages[0].role)
        // Original whitespace is preserved for the transcript and the model (only blank/slash detection trims).
        assertEquals("  hello  ", state.messages[0].content)
        assertEquals(MessageRole.Assistant, state.messages[1].role)
        assertEquals("Hi back", state.messages[1].content)
        assertEquals(usage, state.lastUsage)
        assertFalse(state.isAwaitingResponse)
    }

    @Test
    fun `model command switches the model for subsequent turns`() {
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult(text = "new model answer", toolCalls = emptyList(), usage = null),
        )
        val state = AppState(modelName = context.modelName)
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context)
        val discovery = FoundryDiscoveryCommand(
            state,
            loop,
            MockFoundryDeploymentCatalog(listOf(FoundryDeployment("gpt-next", "ModelDeployment"))),
            MockFoundryConnectionCatalog(),
        )
        val controller = controller(state, loop, discoveryCommand = discovery)

        assertTrue(controller.submit("/model gpt-next"))
        assertEquals("gpt-next", loop.modelName)
        assertEquals("gpt-next", state.modelName)

        assertTrue(controller.submit("hello"))

        assertEquals("gpt-next", mock.requests.single().model)
        assertTrue(state.messages.any { it.role == MessageRole.System && it.content.contains("Switched model") })
    }

    @Test
    fun `model command is rejected when a persisted agent is bound`() {
        val responses = MockBindingFoundryResponsesClient().also { it.bindAgent("my-agent") }
        val management = ProviderManagement.PromptAgents(responses, MockControllerPromptAgentClient)
        val runtime = ProviderRuntime(PromptProvider(responses), management)
        val state = AppState(modelName = context.modelName, activeAgentName = "my-agent")
        val loop = AgentLoop(runtime, NoToolExecutor, context)
        val controller = controller(state, loop)

        assertTrue(controller.submit("/model gpt-next"))

        // The bound agent supplies its own baked-in model, so nothing switches and the user is told why.
        assertEquals(context.modelName, loop.modelName)
        assertEquals(context.modelName, state.modelName)
        assertTrue(
            state.messages.any { it.role == MessageRole.System && it.content.contains("fixed by the bound agent") },
        )
    }

    @Test
    fun `Hosted commands reject client compaction and model switching without running the provider`() {
        var turns = 0
        val provider = object : AgentProvider {
            override val kind: AgentKind = AgentKind.Hosted
            override val capabilities: ProviderCapabilities = ProviderCapabilities.Hosted
            override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
                turns++
            }
            override suspend fun close() = Unit
        }
        val state = AppState(modelName = context.modelName)
        val loop = AgentLoop(provider, NoToolExecutor, context)
        val controller = controller(state, loop)

        assertTrue(controller.submit("/compact"))
        assertTrue(controller.submit("/model gpt-next"))

        assertEquals(0, turns)
        assertEquals(context.modelName, loop.modelName)
        assertEquals(context.modelName, state.modelName)
        assertTrue(state.messages.any { it.content.contains("/compact is unavailable") })
        assertTrue(state.messages.any { it.content.contains("/model is unavailable") })
    }

    @Test
    fun `model command without argument reports the active model without running a turn`() {
        val (controller, state) = controllerWith()

        assertTrue(controller.submit("/model"))

        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.System, state.messages[0].role)
        assertTrue(state.messages[0].content.contains("Active model: gpt-test"))
    }

    @Test
    fun `Foundry Responses failure surfaces an error message and keeps running`() {
        // No queued response -> the mock throws, exercising the provider's Failed path.
        val (controller, state) = controllerWith()

        val shouldContinue = controller.submit("hello")

        assertTrue(shouldContinue)
        assertEquals(2, state.messages.size)
        assertEquals(MessageRole.User, state.messages[0].role)
        assertEquals(MessageRole.System, state.messages[1].role)
        assertTrue(state.messages[1].content.startsWith("⚠"))
        assertFalse(state.isAwaitingResponse)
    }

    @Test
    fun `agent command without a persisted-agent client is intercepted and reported as prompt-only`() {
        // agentCommand defaults to null here; /agent must be handled locally, never reaching the model/loop
        // (no queued response is set, so a leaked turn would surface a Failed error instead).
        val (controller, state) = controllerWith()

        val shouldContinue = controller.submit("/agent")

        assertTrue(shouldContinue)
        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.System, state.messages[0].role)
        assertTrue(state.messages[0].content.contains("Prompt provider"))
    }

    @Test
    fun `agent command is intercepted regardless of case or whitespace separator`() {
        // Mixed case + a tab separator must still be caught locally, not sent to the model.
        val (controller, state) = controllerWith()

        val shouldContinue = controller.submit("/Agent\tlist")

        assertTrue(shouldContinue)
        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.System, state.messages[0].role)
        assertTrue(state.messages[0].content.contains("Prompt provider"))
    }

    @Test
    fun routesPromptAgentImmediately() = runBlocking {
        val responses = MockBindingFoundryResponsesClient()
        val state = AppState(modelName = context.modelName)
        val loop = AgentLoop(PromptProvider(responses), NoToolExecutor, context)
        val command = PromptAgentCommand(
            state,
            { context },
            responses,
            MockControllerPromptAgentClient,
            recordAgent = {},
        )
        val controller = controller(state, loop, command)

        val submission = controller.submitAsync("/AGENT Use Billing", this) { it() }

        assertEquals(ConversationController.Submission.Handled, submission)
        assertEquals("Billing", responses.activeAgent)
        assertEquals("Billing", state.activeAgentName)
    }

    @Test
    fun `hosted log frames render as system lines`() {
        val assistant = AssistantEntry(id = Uuid.random(), parentId = null, timestamp = Clock.System.now(), text = "done")
        val provider = object : AgentProvider {
            override val kind = AgentKind.Hosted
            override val capabilities: ProviderCapabilities = ProviderCapabilities.Hosted
            override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
                emit(AgentEvent.LogFrame("container booting"))
                emit(AgentEvent.TextDelta("done"))
                emit(AgentEvent.TurnCompleted(assistant))
            }
            override suspend fun close() = Unit
        }
        val state = AppState()
        val loop = AgentLoop(provider, NoToolExecutor, context)
        val controller = controller(state, loop)

        controller.submit("hello hosted")

        // user, 📋 log line, assistant final answer
        assertEquals(3, state.messages.size)
        assertEquals(MessageRole.System, state.messages[1].role)
        assertTrue(state.messages[1].content.contains("container booting"))
        assertEquals(MessageRole.Assistant, state.messages[2].role)
        assertEquals("done", state.messages[2].content)
    }

    @Test
    fun `retry status is formatted by the selected frontend catalog`() {
        val assistant = AssistantEntry(id = Uuid.random(), parentId = null, timestamp = Clock.System.now(), text = "done")
        val provider = object : AgentProvider {
            override val kind = AgentKind.Prompt
            override val capabilities: ProviderCapabilities =
                ProviderCapabilities.prompt(promptAgentManagement = false)
            override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
                emit(AgentEvent.Retrying("HTTP 429", retryAttempt = 1, maxRetries = 3, delayMs = 250))
                emit(AgentEvent.TurnCompleted(assistant))
            }
            override suspend fun close() = Unit
        }
        val state = AppState()
        val loop = AgentLoop(provider, NoToolExecutor, context)
        val controller = controller(
            state,
            loop,
            strings = AppStrings.forLocale(Locale.FRENCH),
        )

        controller.submit("hello")

        assertTrue(state.messages.any { it.content.startsWith("Erreur transitoire du modèle") })
    }

    @Test
    fun `tool calls render as system lines before the final answer`() {
        val toolCall = ToolCall("call-1", "read", """{"path":"x"}""")
        val executor = ToolExecutor { call -> ToolResult(call.callId, "file body line 1\nline 2") }
        val (controller, state) = controllerWith(
            executor,
            FoundryResponsesResult(text = "", toolCalls = listOf(toolCall), usage = null),
            FoundryResponsesResult(text = "all done", toolCalls = emptyList(), usage = null),
        )

        controller.submit("read x")

        // user, ⚙ started, ✓ completed, assistant final answer
        assertEquals(4, state.messages.size)
        assertEquals(MessageRole.User, state.messages[0].role)
        assertEquals(MessageRole.System, state.messages[1].role)
        assertTrue(state.messages[1].content.startsWith("⚙ read"))
        assertEquals(MessageRole.System, state.messages[2].role)
        assertTrue(state.messages[2].content.contains("✓ read"))
        assertEquals(MessageRole.Assistant, state.messages[3].role)
        assertEquals("all done", state.messages[3].content)
    }

    @Test
    fun submitAsyncHandlesDiscovery() = runBlocking {
        val (controller, state) = controllerWith()
        assertEquals(ConversationController.Submission.Quit, controller.submitAsync("/quit", this) { it() })
        val discovery = assertIs<ConversationController.Submission.Turn>(
            controller.submitAsync("/model", this) { it() },
        )
        discovery.job.join()
        assertTrue(state.messages.none { it.role == MessageRole.User })
    }

    @Test
    fun `submitAsync runs a turn, folds the answer, and clears the awaiting flag`() = runBlocking {
        val (controller, state) = controllerWith(FoundryResponsesResult("hi there", emptyList(), null))

        val submission = controller.submitAsync("hello", this) { it() }

        assertIs<ConversationController.Submission.Turn>(submission)
        submission.job.join()
        assertTrue(state.messages.any { it.content == "hi there" })
        assertFalse(state.isAwaitingResponse)
    }

    @Test
    fun `submitAsync turn is cancelable and still clears the awaiting flag`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val state = AppState()
        val loop = AgentLoop(PromptProvider(GatedFoundryResponsesClient(started, gate)), NoToolExecutor, context)
        val controller = controller(state, loop)

        val job = (controller.submitAsync("go", this) { it() } as ConversationController.Submission.Turn).job
        started.await() // the turn is suspended inside a Foundry Responses call
        job.cancel()
        job.join()

        assertFalse(state.isAwaitingResponse) // the turn's finally cleared it even under cancellation
    }

    @Test
    fun `submitAsync routes compact through the async turn path`() = runBlocking {
        val (controller, _) = controllerWith(FoundryResponsesResult("summary", emptyList(), null))

        val submission = controller.submitAsync("/compact", this) { it() }

        // /compact runs a summarization Foundry Responses call, so it is launched as a cancelable Turn (not a
        // synchronous Handled) to avoid blocking the event loop.
        assertIs<ConversationController.Submission.Turn>(submission)
        submission.job.join()
    }
}

private class MockBindingFoundryResponsesClient : FoundryResponsesClient, PromptAgentBinder {
    override var activeAgent: String? = null
        private set

    override fun bindAgent(agentName: String?) {
        activeAgent = agentName?.trim()?.ifBlank { null }
    }

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")
    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = emptyFlow()
    override suspend fun close() = Unit
}

private object MockControllerPromptAgentClient : PromptAgentClient {
    override suspend fun listAgents(): List<String> = emptyList()

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef = PromptAgentRef(name, "1")
}

/** Responses stub that signals [started] when a turn begins, then suspends on [gate] so a test can cancel it. */
private class GatedFoundryResponsesClient(
    private val started: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : FoundryResponsesClient {
    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")
    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
        started.complete(Unit)
        gate.await()
        emit(FoundryResponsesEvent.Completed(FoundryResponsesResult("late", emptyList(), null)))
    }
    override suspend fun close() = Unit
}
