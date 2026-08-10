package com.konductor.agent

import com.konductor.compaction.CompactionSettings
import com.konductor.compaction.TokenEstimator
import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.PromptProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.inference.FoundryResponsesEvent
import com.konductor.provider.inference.FoundryResponsesClient
import com.konductor.provider.inference.FoundryResponsesRequest
import com.konductor.provider.inference.FoundryResponsesResult
import com.konductor.provider.inference.MockFoundryResponsesClient
import com.konductor.provider.inference.PromptContextOverflowException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AgentLoopTest {
    private val context = AgentContext(
        systemPrompt = "sys",
        tools = emptyList(),
        modelName = "gpt-test",
        temperature = null,
    )

    @Test
    fun `runTurn appends user and assistant entries and re-sends the full transcript`() {
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult("first answer", emptyList(), Usage(1, 2, 3)),
            FoundryResponsesResult("second answer", emptyList(), Usage(4, 5, 9)),
        )
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context)

        val turn1 = runBlocking { loop.runTurn("hello").toList() }
        assertIs<AgentEvent.TurnCompleted>(turn1.last())
        assertEquals(2, loop.history.size)
        assertIs<UserEntry>(loop.history[0])
        assertIs<AssistantEntry>(loop.history[1])
        // The first request carried only the user entry.
        assertEquals(1, mock.requests[0].history.size)

        runBlocking { loop.runTurn("again").toList() }
        assertEquals(4, loop.history.size)
        // The second request re-sent the reconstructed transcript: user, assistant, user.
        assertEquals(3, mock.requests[1].history.size)
    }

    @Test
    fun `runTurn folds tool call and result entries into history`() {
        val toolCall = ToolCall("call-1", "read", """{"path":"x"}""")
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult("", listOf(toolCall), null),
            FoundryResponsesResult("done", emptyList(), Usage(1, 1, 2)),
        )
        val executor = ToolExecutor { call -> ToolResult(call.callId, "body") }
        val loop = AgentLoop(PromptProvider(mock), executor, context)

        runBlocking { loop.runTurn("read x").toList() }

        // The persisted transcript now includes the tool interaction: user, tool call, tool result, assistant.
        assertEquals(4, loop.history.size)
        assertIs<UserEntry>(loop.history[0])
        val callEntry = assertIs<ToolCallEntry>(loop.history[1])
        assertEquals("call-1", callEntry.call.callId)
        val resultEntry = assertIs<ToolResultEntry>(loop.history[2])
        assertEquals("body", resultEntry.result.output)
        assertIs<AssistantEntry>(loop.history[3])

        // The in-turn re-request already carried user + tool call + tool result.
        assertEquals(3, mock.requests[1].history.size)
    }

    @Test
    fun `a later turn re-sends prior tool call and result entries`() {
        val toolCall = ToolCall("call-1", "read", """{"path":"x"}""")
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult("", listOf(toolCall), null), // turn 1, request #0 -> asks for a tool
            FoundryResponsesResult("first", emptyList(), null), // turn 1, request #1 -> final answer
            FoundryResponsesResult("second", emptyList(), null), // turn 2, request #2 -> final answer
        )
        val executor = ToolExecutor { call -> ToolResult(call.callId, "body") }
        val loop = AgentLoop(PromptProvider(mock), executor, context)

        runBlocking { loop.runTurn("read x").toList() }
        runBlocking { loop.runTurn("thanks").toList() }

        // The second turn's request must still carry turn 1's tool call + result — the durability guarantee.
        val turn2History = mock.requests[2].history
        assertTrue(turn2History.any { it is ToolCallEntry && it.call.callId == "call-1" }, "missing tool call")
        assertTrue(turn2History.any { it is ToolResultEntry && it.result.output == "body" }, "missing tool result")
    }

    @Test
    fun `overlapping runTurn collections are rejected without mutating the session`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loop = AgentLoop(PromptProvider(GatedFoundryResponsesClient(started, release)), NoToolExecutor, context)

        val first = async { loop.runTurn("first").toList() }
        started.await()

        val overlap = runCatching { loop.runTurn("second").toList() }.exceptionOrNull()

        assertIs<TurnAlreadyInProgressException>(overlap)
        assertEquals(listOf("first"), loop.history.filterIsInstance<UserEntry>().map { it.text })

        release.complete(Unit)
        first.await()
        assertEquals(2, loop.history.size)
    }

    @Test
    fun `cancellation during reactive summary emits no failure and starts no retry`() = runBlocking {
        val provider = CancellableRecoveryProvider(cancelDuringSummary = true)
        val loop = recoveryLoop(provider)
        val events = mutableListOf<AgentEvent>()
        val collector = launch { loop.runTurn("accepted").collect { events += it } }
        provider.stageStarted.await()

        collector.cancelAndJoin()

        assertTrue(events.none { it is AgentEvent.Failed })
        assertEquals(2, provider.calls)
        assertEquals(1, loop.session.entries.filterIsInstance<UserEntry>().count { it.text == "accepted" })
        assertTrue(loop.session.entries.none { it is com.konductor.core.models.CompactionEntry })
    }

    @Test
    fun `cancellation during recovery retry keeps committed marker and does not replay again`() = runBlocking {
        val provider = CancellableRecoveryProvider(cancelDuringSummary = false)
        val loop = recoveryLoop(provider)
        val events = mutableListOf<AgentEvent>()
        val collector = launch { loop.runTurn("accepted").collect { events += it } }
        provider.stageStarted.await()

        collector.cancelAndJoin()

        assertTrue(events.none { it is AgentEvent.Failed })
        assertEquals(3, provider.calls)
        assertEquals(1, events.filterIsInstance<AgentEvent.Compacted>().size)
        assertEquals(1, loop.session.entries.filterIsInstance<UserEntry>().count { it.text == "accepted" })
        assertEquals(1, loop.session.entries.filterIsInstance<com.konductor.core.models.CompactionEntry>().size)
    }

    @Test
    fun `close delegates to the provider and Foundry Responses client`() {
        val mock = MockFoundryResponsesClient()
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context)

        runBlocking { loop.close() }

        assertTrue(mock.closed)
    }

    private fun recoveryLoop(provider: com.konductor.provider.AgentProvider): AgentLoop {
        val loop = AgentLoop(
            provider,
            NoToolExecutor,
            context,
            compaction = CompactionSettings(enabled = false, keepRecentTokens = 5),
        )
        val timestamp = Instant.parse("2026-07-30T00:00:00Z")
        repeat(3) { index ->
            val user = UserEntry(
                Uuid.random(),
                loop.session.entries.lastOrNull()?.id,
                timestamp,
                "u$index ${"x".repeat(10 * TokenEstimator.CHARS_PER_TOKEN)}",
            )
            loop.session.entries += user
            loop.session.entries += AssistantEntry(
                Uuid.random(),
                user.id,
                timestamp,
                "a$index ${"x".repeat(10 * TokenEstimator.CHARS_PER_TOKEN)}",
            )
        }
        return loop
    }
}

private class CancellableRecoveryProvider(
    private val cancelDuringSummary: Boolean,
) : com.konductor.provider.AgentProvider {
    override val kind: com.konductor.provider.AgentKind = com.konductor.provider.AgentKind.Prompt
    override val capabilities: com.konductor.provider.ProviderCapabilities =
        com.konductor.provider.ProviderCapabilities.prompt(promptAgentManagement = false)
    val stageStarted = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<Unit>()
    var calls = 0
        private set

    override fun runTurn(request: com.konductor.provider.TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        calls++
        when (calls) {
            1 -> emit(AgentEvent.Failed(PromptContextOverflowException(IllegalStateException("overflow"))))
            2 -> if (cancelDuringSummary) {
                stageStarted.complete(Unit)
                never.await()
            } else {
                emit(
                    AgentEvent.TurnCompleted(
                        AssistantEntry(Uuid.random(), null, Clock.System.now(), "SUMMARY"),
                    ),
                )
            }
            3 -> {
                stageStarted.complete(Unit)
                never.await()
            }
            else -> error("unexpected replay")
        }
    }

    override suspend fun close() = Unit
}

private class GatedFoundryResponsesClient(
    private val started: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
) : FoundryResponsesClient {
    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
        started.complete(Unit)
        release.await()
        emit(FoundryResponsesEvent.Completed(FoundryResponsesResult("done", emptyList(), null)))
    }

    override suspend fun close() = Unit
}
