package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.core.AppState
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.Usage
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.PromptProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceChunk
import com.konductor.provider.inference.InferenceClient
import com.konductor.provider.inference.InferenceRequest
import com.konductor.provider.inference.InferenceResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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

    private fun controllerWith(vararg responses: InferenceResponse): Pair<ConversationController, AppState> =
        controllerWith(NoToolExecutor, *responses)

    private fun controllerWith(
        toolExecutor: ToolExecutor,
        vararg responses: InferenceResponse,
    ): Pair<ConversationController, AppState> {
        val state = AppState()
        val loop = AgentLoop(PromptProvider(FakeInferenceClient(*responses)), toolExecutor, context)
        return ConversationController(state, loop) to state
    }

    @Test
    fun `blank input is ignored and keeps the app running`() {
        val (controller, state) = controllerWith()

        val shouldContinue = controller.submit("   ")

        assertTrue(shouldContinue)
        assertEquals(emptyList(), state.messages)
    }

    @Test
    fun `quit commands stop the app without adding messages`() {
        val (controller, state) = controllerWith()

        assertFalse(controller.submit("/quit"))
        assertFalse(controller.submit("/EXIT"))
        assertEquals(emptyList(), state.messages)
    }

    @Test
    fun `submitted text preserves whitespace and renders the model answer and token usage`() {
        val usage = Usage(inputTokens = 7, outputTokens = 3, totalTokens = 10)
        val (controller, state) = controllerWith(
            InferenceResponse(text = "Hi back", toolCalls = emptyList(), usage = usage),
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
        val fake = FakeInferenceClient(InferenceResponse(text = "new model answer", toolCalls = emptyList(), usage = null))
        val state = AppState(modelName = context.modelName)
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context)
        val controller = ConversationController(state, loop)

        assertTrue(controller.submit("/model gpt-next"))
        assertEquals("gpt-next", loop.modelName)
        assertEquals("gpt-next", state.modelName)

        assertTrue(controller.submit("hello"))

        assertEquals("gpt-next", fake.requests.single().model)
        assertTrue(state.messages.any { it.role == MessageRole.System && it.content.contains("Switched model") })
    }

    @Test
    fun `model command is rejected when a persisted agent is bound`() {
        val fake = FakeInferenceClient(InferenceResponse(text = "unused", toolCalls = emptyList(), usage = null))
        val state = AppState(modelName = context.modelName, activeAgentName = "my-agent")
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context)
        val controller = ConversationController(state, loop)

        assertTrue(controller.submit("/model gpt-next"))

        // The bound agent supplies its own baked-in model, so nothing switches and the user is told why.
        assertEquals(context.modelName, loop.modelName)
        assertEquals(context.modelName, state.modelName)
        assertTrue(
            state.messages.any { it.role == MessageRole.System && it.content.contains("fixed by the bound agent") },
        )
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
    fun `inference failure surfaces an error message and keeps running`() {
        // No queued response -> the fake throws, exercising the provider's Failed path.
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
    fun `hosted log frames render as system lines`() {
        val assistant = AssistantEntry(id = Uuid.random(), parentId = null, timestamp = Clock.System.now(), text = "done")
        val provider = object : AgentProvider {
            override val kind = AgentKind.Hosted
            override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
                emit(AgentEvent.LogFrame("container booting"))
                emit(AgentEvent.TextDelta("done"))
                emit(AgentEvent.TurnCompleted(assistant))
            }
            override suspend fun close() = Unit
        }
        val state = AppState()
        val controller = ConversationController(state, AgentLoop(provider, NoToolExecutor, context))

        controller.submit("hello hosted")

        // user, 📋 log line, assistant final answer
        assertEquals(3, state.messages.size)
        assertEquals(MessageRole.System, state.messages[1].role)
        assertTrue(state.messages[1].content.contains("container booting"))
        assertEquals(MessageRole.Assistant, state.messages[2].role)
        assertEquals("done", state.messages[2].content)
    }

    @Test
    fun `tool calls render as system lines before the final answer`() {
        val toolCall = ToolCall("call-1", "read", """{"path":"x"}""")
        val executor = ToolExecutor { call -> ToolResult(call.callId, "file body line 1\nline 2") }
        val (controller, state) = controllerWith(
            executor,
            InferenceResponse(text = "", toolCalls = listOf(toolCall), usage = null),
            InferenceResponse(text = "all done", toolCalls = emptyList(), usage = null),
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
    fun `submitAsync handles quit and commands without launching a turn`() = runBlocking {
        val (controller, state) = controllerWith()
        assertEquals(ConversationController.Submission.Quit, controller.submitAsync("/quit", this) { it() })
        assertEquals(ConversationController.Submission.Handled, controller.submitAsync("/model", this) { it() })
        assertTrue(state.messages.none { it.role == MessageRole.User })
    }

    @Test
    fun `submitAsync runs a turn, folds the answer, and clears the awaiting flag`() = runBlocking {
        val (controller, state) = controllerWith(InferenceResponse("hi there", emptyList(), null))

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
        val loop = AgentLoop(PromptProvider(GatedInferenceClient(started, gate)), NoToolExecutor, context)
        val controller = ConversationController(state, loop)

        val job = (controller.submitAsync("go", this) { it() } as ConversationController.Submission.Turn).job
        started.await() // the turn is suspended inside inference
        job.cancel()
        job.join()

        assertFalse(state.isAwaitingResponse) // the turn's finally cleared it even under cancellation
    }
}

/** Inference stub that signals [started] when a turn begins, then suspends on [gate] so a test can cancel it. */
private class GatedInferenceClient(
    private val started: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : InferenceClient {
    override suspend fun respond(request: InferenceRequest): InferenceResponse = error("unused")
    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
        started.complete(Unit)
        gate.await()
        emit(InferenceChunk.Completed(InferenceResponse("late", emptyList(), null)))
    }
    override suspend fun close() = Unit
}
