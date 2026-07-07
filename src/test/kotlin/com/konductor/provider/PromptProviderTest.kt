package com.konductor.provider

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Entry
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
}
