package com.konductor.agent

import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.UserEntry
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceResponse
import com.konductor.core.models.Usage
import com.konductor.session.JsonlSessionStore
import kotlinx.coroutines.flow.toList
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
}
