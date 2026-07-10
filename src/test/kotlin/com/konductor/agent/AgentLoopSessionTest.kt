package com.konductor.agent

import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.PromptProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceChunk
import com.konductor.provider.inference.InferenceClient
import com.konductor.provider.inference.InferenceRequest
import com.konductor.provider.inference.InferenceResponse
import com.konductor.core.models.Usage
import com.konductor.session.JsonlSessionStore
import com.konductor.session.SessionStore
import com.konductor.session.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AgentLoopSessionTest {
    private val context = AgentContext(systemPrompt = "sys", tools = emptyList(), modelName = "gpt-test", temperature = null)

    @Test
    fun `produced entries are persisted and survive a reload`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("proj"), context.modelName, null)
        val fake = FakeInferenceClient(InferenceResponse("hello answer", emptyList(), Usage(1, 2, 3)))
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context, store, session)

        runBlocking { loop.runTurn("hi").toList() }

        val reloaded = JsonlSessionStore(root).load(session.id)
        assertEquals(2, reloaded.entries.size)
        assertEquals("hi", assertIs<UserEntry>(reloaded.entries[0]).text)
        assertEquals("hello answer", assertIs<AssistantEntry>(reloaded.entries[1]).text)
    }

    @Test
    fun `newSession retargets to a fresh session and leaves the old one on disk`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, "first")
        val fake = FakeInferenceClient(
            InferenceResponse("a", emptyList(), null),
            InferenceResponse("b", emptyList(), null),
        )
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context, store, session)
        runBlocking { loop.runTurn("one").toList() }
        val firstId = loop.session.id

        val fresh = loop.newSession()
        assertNotEquals(firstId, fresh.id)
        assertTrue(loop.history.isEmpty())

        runBlocking { loop.runTurn("two").toList() }
        assertEquals(2, loop.history.size) // only the new session's user + assistant
        assertEquals(2, JsonlSessionStore(root).load(firstId).entries.size) // first session intact on disk
    }

    @Test
    fun `resume loads a previously persisted session as the active transcript`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("p")
        val first = store.create(cwd, context.modelName, null)
        store.append(first, UserEntry(Uuid.random(), null, Instant.parse("2026-07-08T10:00:00Z"), "remembered"))

        val current = store.create(cwd, context.modelName, null)
        val loop = AgentLoop(PromptProvider(FakeInferenceClient()), NoToolExecutor, context, store, current)

        val resumed = loop.resume(first.id)

        assertEquals(first.id, resumed.id)
        assertEquals(1, loop.history.size)
        assertEquals("remembered", assertIs<UserEntry>(loop.history[0]).text)
    }

    @Test
    fun `rename persists the label`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val loop = AgentLoop(PromptProvider(FakeInferenceClient()), NoToolExecutor, context, store, session)

        loop.rename("labeled")

        assertEquals("labeled", JsonlSessionStore(root).load(session.id).name)
    }

    @Test
    fun `a persistence failure fails the turn but does not crash out of the flow`(@TempDir root: Path) {
        // A store whose append() always throws (disk full / lock / permission). The failure must surface as a
        // recoverable AgentEvent.Failed, not propagate out of runTurn and take the app down.
        val failing = object : SessionStore {
            override fun create(cwd: Path, model: String, name: String?): Session =
                Session(Uuid.random(), name, cwd, model, Instant.parse("2026-07-08T10:00:00Z"))
            override fun append(session: Session, entry: Entry): Unit = error("disk full")
            override fun load(id: Uuid): Session = throw UnsupportedOperationException()
            override fun listForCwd(cwd: Path): List<SessionSummary> = emptyList()
            override fun rename(session: Session, name: String) = Unit
        }
        val session = failing.create(root, context.modelName, null)
        val loop = AgentLoop(
            PromptProvider(FakeInferenceClient(InferenceResponse("hi", emptyList(), null))),
            NoToolExecutor,
            context,
            failing,
            session,
        )

        val events = runBlocking { loop.runTurn("hello").toList() }

        assertTrue(events.any { it is AgentEvent.Failed }, "expected a Failed event from the persist failure")
    }

    @Test
    fun `persisted assistant entry chains parentId to the real last entry after a tool turn`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val fake = FakeInferenceClient(
            InferenceResponse("", listOf(ToolCall("c1", "read", "{}")), null),
            InferenceResponse("done", emptyList(), null),
        )
        val executor = ToolExecutor { call -> ToolResult(call.callId, "body") }
        val loop = AgentLoop(PromptProvider(fake), executor, context, store, session)

        runBlocking { loop.runTurn("read x").toList() }

        // Persisted order: user, tool_call, tool_result, assistant. The assistant must chain to the tool_result
        // that actually lives in session.entries — not the provider's discarded working-copy id.
        val entries = JsonlSessionStore(root).load(session.id).entries
        val assistant = assertIs<AssistantEntry>(entries.last())
        assertEquals(entries[entries.size - 2].id, assistant.parentId)
    }

    @Test
    fun `partial stream failure keeps the user but persists no partial assistant`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val inference = PartialFailureThenSuccessInferenceClient()
        val loop = AgentLoop(PromptProvider(inference), NoToolExecutor, context, store, session)

        val failedEvents = runBlocking { loop.runTurn("first").toList() }

        assertTrue(failedEvents.any { it is AgentEvent.TextDelta && it.text == "partial" })
        assertTrue(failedEvents.any { it is AgentEvent.Failed })
        val afterFailure = store.load(session.id)
        assertEquals(listOf("first"), afterFailure.entries.filterIsInstance<UserEntry>().map { it.text })
        assertTrue(afterFailure.entries.none { it is AssistantEntry })
        assertEquals(afterFailure.entries, loop.history)

        runBlocking { loop.runTurn("retry").toList() }

        assertEquals(
            listOf("first", "retry"),
            inference.requests[1].history.filterIsInstance<UserEntry>().map { it.text },
        )
        val afterRetry = store.load(session.id)
        assertEquals("recovered", assertIs<AssistantEntry>(afterRetry.entries.last()).text)
        assertTrue(afterRetry.entries.filterIsInstance<AssistantEntry>().none { it.text == "partial" })
    }

    @Test
    fun `cancelled partial stream keeps the user and cancellation stays transparent`(@TempDir root: Path) = runBlocking {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val streamed = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val inference = CancelThenSuccessInferenceClient(streamed, gate)
        val loop = AgentLoop(PromptProvider(inference), NoToolExecutor, context, store, session)
        val events = mutableListOf<AgentEvent>()
        val collector = launch { loop.runTurn("cancelled").collect { events += it } }
        streamed.await()

        collector.cancelAndJoin()

        assertTrue(collector.isCancelled)
        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "partial" })
        assertTrue(events.none { it is AgentEvent.Failed })
        val afterCancel = store.load(session.id)
        assertEquals(listOf("cancelled"), afterCancel.entries.filterIsInstance<UserEntry>().map { it.text })
        assertTrue(afterCancel.entries.none { it is AssistantEntry })
        assertEquals(afterCancel.entries, loop.history)

        loop.runTurn("retry").toList()

        assertEquals(
            listOf("cancelled", "retry"),
            inference.requests[1].history.filterIsInstance<UserEntry>().map { it.text },
        )
        val afterRetry = store.load(session.id)
        assertEquals("recovered", assertIs<AssistantEntry>(afterRetry.entries.last()).text)
        assertTrue(afterRetry.entries.filterIsInstance<AssistantEntry>().none { it.text == "partial" })
    }
}

private class PartialFailureThenSuccessInferenceClient : InferenceClient {
    val requests = mutableListOf<InferenceRequest>()

    override suspend fun respond(request: InferenceRequest): InferenceResponse = error("unused")

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
        requests += request
        if (requests.size == 1) {
            emit(InferenceChunk.TextDelta("partial"))
            error("stream failed")
        }
        emit(InferenceChunk.Completed(InferenceResponse("recovered", emptyList(), null)))
    }

    override suspend fun close() = Unit
}

private class CancelThenSuccessInferenceClient(
    private val streamed: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : InferenceClient {
    val requests = mutableListOf<InferenceRequest>()

    override suspend fun respond(request: InferenceRequest): InferenceResponse = error("unused")

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
        requests += request
        if (requests.size == 1) {
            emit(InferenceChunk.TextDelta("partial"))
            streamed.complete(Unit)
            gate.await()
            emit(InferenceChunk.Completed(InferenceResponse("late", emptyList(), null)))
        } else {
            emit(InferenceChunk.Completed(InferenceResponse("recovered", emptyList(), null)))
        }
    }

    override suspend fun close() = Unit
}
