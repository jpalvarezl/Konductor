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
import com.konductor.provider.inference.InferenceResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
