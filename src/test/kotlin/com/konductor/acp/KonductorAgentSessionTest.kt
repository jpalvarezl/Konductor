package com.konductor.acp

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.compaction.CompactionSettings
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
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
import com.konductor.session.JsonlSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KonductorAgentSessionTest {
    private val context = AgentContext(
        systemPrompt = "sys",
        tools = emptyList(),
        modelName = "gpt-test",
        temperature = null,
    )

    private fun sessionOver(fake: FakeInferenceClient): KonductorAgentSession =
        KonductorAgentSession(SessionId("test-session"), AgentLoop(PromptProvider(fake), NoToolExecutor, context))

    @Test
    fun `prompt streams the model answer then ends the turn`() {
        val session = sessionOver(FakeInferenceClient(InferenceResponse("Hi back", emptyList(), Usage(1, 2, 3))))

        val events = runBlocking {
            session.prompt(listOf(ContentBlock.Text("hello")), _meta = null).toList()
        }

        assertEquals(2, events.size)
        val chunk = (events[0] as Event.SessionUpdateEvent).update as SessionUpdate.AgentMessageChunk
        assertEquals("Hi back", (chunk.content as ContentBlock.Text).text)
        assertEquals(StopReason.END_TURN, (events[1] as Event.PromptResponseEvent).response.stopReason)
    }

    @Test
    fun `prompt joins multiple text blocks into one user turn`() {
        val fake = FakeInferenceClient(InferenceResponse("ok", emptyList(), null))

        runBlocking {
            sessionOver(fake).prompt(
                listOf(ContentBlock.Text("line one"), ContentBlock.Text("line two")),
                _meta = null,
            ).toList()
        }

        val userText = (fake.requests[0].history.last() as UserEntry).text
        assertEquals("line one\nline two", userText)
    }

    @Test
    fun `inference failure is surfaced as a message chunk and still ends the turn`() {
        // No queued response -> the fake throws, exercising the provider's Failed path.
        val session = sessionOver(FakeInferenceClient())

        val events = runBlocking {
            session.prompt(listOf(ContentBlock.Text("hi")), _meta = null).toList()
        }

        assertEquals(2, events.size)
        val chunk = (events[0] as Event.SessionUpdateEvent).update as SessionUpdate.AgentMessageChunk
        assertTrue((chunk.content as ContentBlock.Text).text.startsWith("⚠"))
        assertEquals(StopReason.END_TURN, (events[1] as Event.PromptResponseEvent).response.stopReason)
    }

    @Test
    fun `a no-op-store session keeps history in memory and persists nothing`() {
        // A session over the default NoOpSessionStore (tests + any non-persistent caller): the transcript
        // accumulates across prompts so the provider re-sends full history, but nothing is written to disk.
        // (The ACP frontend itself now persists via JsonlSessionStore — see KonductorAgentSupport, Phase C.)
        val fake = FakeInferenceClient(
            InferenceResponse("first", emptyList(), null),
            InferenceResponse("second", emptyList(), null),
        )
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context)
        val session = KonductorAgentSession(SessionId("acp-1"), loop)

        runBlocking {
            session.prompt(listOf(ContentBlock.Text("one")), _meta = null).toList()
            session.prompt(listOf(ContentBlock.Text("two")), _meta = null).toList()
        }

        assertNull(loop.sessionLocation())
        assertTrue(loop.listSessions().isEmpty())
        // The second turn re-sent the full reconstructed transcript (user, assistant, user).
        assertEquals(3, fake.requests[1].history.size)
        // Transcript accumulated in memory: user, assistant, user, assistant.
        assertEquals(4, loop.history.size)
    }

    @Test
    fun `tool activity is surfaced as tool_call then tool_call_update`() {
        val fake = FakeInferenceClient(
            InferenceResponse("", listOf(ToolCall("c1", "read", "{\"path\":\"x\"}")), null),
            InferenceResponse("done", emptyList(), null),
        )
        val executor = ToolExecutor { call -> ToolResult(call.callId, "file body") }
        val session = KonductorAgentSession(SessionId("s"), AgentLoop(PromptProvider(fake), executor, context))

        val events = runBlocking { session.prompt(listOf(ContentBlock.Text("read x")), _meta = null).toList() }

        val started = events.mapNotNull { (it as? Event.SessionUpdateEvent)?.update as? SessionUpdate.ToolCall }.single()
        assertEquals(ToolKind.READ, started.kind)
        assertEquals(ToolCallStatus.IN_PROGRESS, started.status)
        val completed =
            events.mapNotNull { (it as? Event.SessionUpdateEvent)?.update as? SessionUpdate.ToolCallUpdate }.single()
        assertEquals(ToolCallStatus.COMPLETED, completed.status)
        assertEquals(StopReason.END_TURN, (events.last() as Event.PromptResponseEvent).response.stopReason)
    }

    @Test
    fun `hosted log frames are surfaced to the client as log-prefixed message chunks`() {
        // LogFrame is a hosted-session event, so drive it through a fake provider directly (the Prompt path
        // never emits it).
        val provider = object : AgentProvider {
            override val kind = AgentKind.Hosted
            override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
                emit(AgentEvent.LogFrame("container ready"))
                emit(AgentEvent.LogFrame("running tool"))
            }
            override suspend fun close() = Unit
        }
        val session = KonductorAgentSession(SessionId("s"), AgentLoop(provider, NoToolExecutor, context))

        val events = runBlocking { session.prompt(listOf(ContentBlock.Text("go")), _meta = null).toList() }

        val logs = events.mapNotNull { (it as? Event.SessionUpdateEvent)?.update as? SessionUpdate.AgentMessageChunk }
            .map { (it.content as ContentBlock.Text).text }
        assertEquals(listOf("📋 container ready", "📋 running tool"), logs)
        assertEquals(StopReason.END_TURN, (events.last() as Event.PromptResponseEvent).response.stopReason)
    }

    @Test
    fun `createSession persists so listSessions and loadSession round-trip`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val support = KonductorAgentSupport(
            PromptProvider(FakeInferenceClient()), context, NoToolExecutor, store, CompactionSettings(enabled = false),
        )
        val cwd = root.resolve("proj").toString()
        val params = SessionCreationParameters(cwd, emptyList(), emptyList(), null)

        val created = runBlocking { support.createSession(params) }
        val listed = runBlocking { support.listSessions(cwd, emptyList(), null).toList() }
        val loaded = runBlocking { support.loadSession(listed.single().sessionId, params) }

        assertEquals(created.sessionId, listed.single().sessionId)
        assertEquals(created.sessionId, loaded.sessionId)
    }

    @Test
    fun `session cancel stops the in-flight turn and ends with CANCELLED`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val session = KonductorAgentSession(
            SessionId("s"),
            AgentLoop(PromptProvider(GatedInferenceClient(started, gate)), NoToolExecutor, context),
        )
        val events = mutableListOf<Event>()
        val collector = launch {
            session.prompt(listOf(ContentBlock.Text("go")), _meta = null).collect { events += it }
        }

        started.await() // the turn is now suspended inside inference
        session.cancel()
        collector.join()

        assertEquals(StopReason.CANCELLED, (events.last() as Event.PromptResponseEvent).response.stopReason)
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
