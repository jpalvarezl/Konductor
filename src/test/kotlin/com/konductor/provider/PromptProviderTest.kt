package com.konductor.provider

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceChunk
import com.konductor.provider.inference.InferenceClient
import com.konductor.provider.inference.InferenceRequest
import com.konductor.provider.inference.InferenceResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class PromptProviderTest {
    private val context = AgentContext(
        systemPrompt = "sys",
        tools = emptyList(),
        modelName = "gpt-test",
        temperature = null,
    )

    private val noTools = ToolExecutor { call -> error("unexpected tool ${call.name}") }

    private fun userEntry(text: String): UserEntry =
        UserEntry(id = Uuid.random(), parentId = null, timestamp = Clock.System.now(), text = text)

    // Block-bodied so the @Test method returns Unit; an expression body would return the last value and be
    // silently skipped by JUnit 5.
    @Test
    fun `streams text delta then usage then a completed turn`() {
        val usage = Usage(inputTokens = 10, outputTokens = 5, totalTokens = 15)
        val user = userEntry("hi")
        val provider = PromptProvider(
            FakeInferenceClient(InferenceResponse(text = "hello there", toolCalls = emptyList(), usage = usage)),
        )

        val events = runBlocking {
            provider.runTurn(TurnRequest(context, listOf<Entry>(user)), noTools).toList()
        }

        assertEquals(3, events.size)
        assertEquals(AgentEvent.TextDelta("hello there"), events[0])
        assertEquals(AgentEvent.UsageReported(usage), events[1])
        val completed = assertIs<AgentEvent.TurnCompleted>(events[2])
        assertEquals("hello there", completed.assistant.text)
        assertEquals(usage, completed.assistant.usage)
        assertEquals(user.id, completed.assistant.parentId)
    }

    @Test
    fun `relays every text delta in order before completing`() {
        val streaming = object : InferenceClient {
            override suspend fun respond(request: InferenceRequest): InferenceResponse = error("unused")
            override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
                emit(InferenceChunk.TextDelta("Hel"))
                emit(InferenceChunk.TextDelta("lo, "))
                emit(InferenceChunk.TextDelta("world"))
                emit(InferenceChunk.Completed(InferenceResponse("Hello, world", emptyList(), null)))
            }
            override suspend fun close() = Unit
        }

        val events = runBlocking {
            PromptProvider(streaming).runTurn(TurnRequest(context, listOf<Entry>(userEntry("q"))), noTools).toList()
        }

        val deltas = events.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertEquals(listOf("Hel", "lo, ", "world"), deltas)
        val completed = assertIs<AgentEvent.TurnCompleted>(events.last())
        assertEquals("Hello, world", completed.assistant.text)
    }

    @Test
    fun `response without usage emits a delta then a completed turn`() {
        val provider = PromptProvider(
            FakeInferenceClient(InferenceResponse(text = "answer", toolCalls = emptyList(), usage = null)),
        )

        val events = runBlocking {
            provider.runTurn(TurnRequest(context, listOf<Entry>(userEntry("q"))), noTools).toList()
        }

        assertEquals(2, events.size)
        assertEquals(AgentEvent.TextDelta("answer"), events[0])
        assertIs<AgentEvent.TurnCompleted>(events[1])
    }

    @Test
    fun `inference failure is surfaced as a Failed event`() {
        val boom = object : InferenceClient {
            override suspend fun respond(request: InferenceRequest): InferenceResponse = error("unused")
            override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> =
                flow { throw IllegalStateException("boom") }
            override suspend fun close() = Unit
        }

        val events = runBlocking {
            PromptProvider(boom).runTurn(TurnRequest(context, listOf<Entry>(userEntry("q"))), noTools).toList()
        }

        val failed = assertIs<AgentEvent.Failed>(events.single())
        assertTrue(failed.error is IllegalStateException)
    }

    @Test
    fun `services a tool call then re-requests with the reconstructed tool history`() {
        val toolCall = ToolCall(callId = "call-1", name = "read", argumentsJson = """{"path":"x"}""")
        val fake = FakeInferenceClient(
            InferenceResponse(text = "", toolCalls = listOf(toolCall), usage = null),
            InferenceResponse(text = "done", toolCalls = emptyList(), usage = Usage(1, 1, 2)),
        )
        val executor = ToolExecutor { call -> ToolResult(call.callId, "file body") }
        val user = userEntry("read x")

        val events = runBlocking {
            PromptProvider(fake).runTurn(TurnRequest(context, listOf<Entry>(user)), executor).toList()
        }

        val started = assertIs<AgentEvent.ToolCallStarted>(events[0])
        assertEquals("read", started.call.name)
        val completed = assertIs<AgentEvent.ToolCallCompleted>(events[1])
        assertEquals("file body", completed.result.output)
        val turn = assertIs<AgentEvent.TurnCompleted>(events.last())
        assertEquals("done", turn.assistant.text)

        // The loop re-requests, and the second request carries the reconstructed tool call + result so the
        // model can see its own tool output.
        assertEquals(2, fake.requests.size)
        val history = fake.requests[1].history
        val callEntry = assertIs<ToolCallEntry>(history[history.size - 2])
        assertEquals("call-1", callEntry.call.callId)
        val resultEntry = assertIs<ToolResultEntry>(history.last())
        assertEquals("call-1", resultEntry.result.callId)
        assertEquals("file body", resultEntry.result.output)
    }

    @Test
    fun `stops the turn after the max tool iterations when the model never converges`() {
        // A model that asks for a tool every round and never returns a final answer. Vary the arguments each
        // round so the duplicate short-circuit (B) does NOT fire — this isolates the iteration cap (A).
        val neverConverges = object : InferenceClient {
            private var n = 0
            override suspend fun respond(request: InferenceRequest): InferenceResponse = error("unused")
            override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
                val call = ToolCall(callId = "c$n", name = "read", argumentsJson = """{"path":"x$n"}""")
                n++
                emit(InferenceChunk.Completed(InferenceResponse("", listOf(call), null)))
            }
            override suspend fun close() = Unit
        }
        var executions = 0
        val executor = ToolExecutor { call -> executions++; ToolResult(call.callId, "body") }

        val events = runBlocking {
            PromptProvider(neverConverges, maxToolIterations = 3)
                .runTurn(TurnRequest(context, listOf<Entry>(userEntry("go"))), executor).toList()
        }

        assertEquals(3, executions) // exactly maxToolIterations rounds ran, then the loop stopped itself
        val completed = assertIs<AgentEvent.TurnCompleted>(events.last())
        assertTrue(completed.assistant.text.contains("Stopped after 3 tool iterations"))
    }

    @Test
    fun `skips re-executing a tool call identical to the immediately preceding one`() {
        val dup = ToolCall(callId = "c1", name = "edit", argumentsJson = """{"path":"a","oldString":"x","newString":"y"}""")
        // Same edit twice (different callId, identical name+args), then a final answer.
        val fake = FakeInferenceClient(
            InferenceResponse("", listOf(dup), null),
            InferenceResponse("", listOf(dup.copy(callId = "c2")), null),
            InferenceResponse("done", emptyList(), Usage(1, 1, 2)),
        )
        var executions = 0
        val executor = ToolExecutor { call ->
            executions++
            ToolResult(call.callId, "edit: oldString not found in a", isError = true)
        }

        val events = runBlocking {
            PromptProvider(fake).runTurn(TurnRequest(context, listOf<Entry>(userEntry("edit a"))), executor).toList()
        }

        assertEquals(1, executions) // the second identical call was short-circuited, not executed
        val completions = events.filterIsInstance<AgentEvent.ToolCallCompleted>()
        assertEquals(2, completions.size)
        assertEquals("edit: oldString not found in a", completions[0].result.output) // real, executed result
        assertTrue(completions[1].result.isError)
        assertTrue(completions[1].result.output.contains("identical to your previous call"))
        assertTrue(completions[1].result.output.contains("It previously returned:")) // quotes the prior output
        assertIs<AgentEvent.TurnCompleted>(events.last())
    }
}
