package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.compaction.CompactionSettings
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Session
import com.konductor.core.models.UserEntry
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceResponse
import com.konductor.session.JsonlSessionStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SessionCommandsTest {
    private val context = AgentContext(systemPrompt = "sys", tools = emptyList(), modelName = "gpt-test", temperature = null)

    private fun loop(store: JsonlSessionStore, session: Session, vararg responses: InferenceResponse): AgentLoop =
        AgentLoop(PromptProvider(FakeInferenceClient(*responses)), NoToolExecutor, context, store, session)

    @Test
    fun `slash new starts a fresh session and clears the transcript`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val state = AppState(initialMessages = listOf(ChatMessage(MessageRole.User, "stale line")))
        val agentLoop = loop(store, session)
        val controller = ConversationController(state, agentLoop)

        assertTrue(controller.submit("/new"))

        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.System, state.messages[0].role)
        assertTrue(state.messages[0].content.contains("new session"))
        assertNotEquals(session.id, agentLoop.session.id)
    }

    @Test
    fun `slash name renames and persists`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val state = AppState()
        val controller = ConversationController(state, loop(store, session))

        controller.submit("/name My Work")

        assertEquals("My Work", session.name)
        assertEquals("My Work", JsonlSessionStore(root).load(session.id).name)
        assertTrue(state.messages.last().content.contains("My Work"))
    }

    @Test
    fun `slash name without an argument shows usage`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val state = AppState()
        ConversationController(state, loop(store, session)).submit("/name")

        assertTrue(state.messages.last().content.contains("Usage: /name"))
    }

    @Test
    fun `slash session reports the active session without running a turn`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, "labelled")
        val state = AppState()
        // No queued responses: if a turn ran, the fake would throw. It must not.
        ConversationController(state, loop(store, session)).submit("/session")

        assertEquals(1, state.messages.size)
        val info = state.messages[0].content
        assertTrue(info.contains(session.id.toString().take(8)))
        assertTrue(info.contains("labelled"))
    }

    @Test
    fun `slash resume with no saved sessions reports none`() {
        val state = AppState()
        // Default in-memory store: nothing is ever listed.
        val controller = ConversationController(state, AgentLoop(PromptProvider(FakeInferenceClient()), NoToolExecutor, context))

        controller.submit("/resume")

        assertEquals(1, state.messages.size)
        assertTrue(state.messages[0].content.contains("No saved sessions"))
    }

    @Test
    fun `slash resume lists saved sessions`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("p")
        store.create(cwd, context.modelName, "saved-one")
        val current = store.create(cwd, context.modelName, null)
        val state = AppState()
        ConversationController(state, loop(store, current)).submit("/resume")

        assertTrue(state.messages.last().content.contains("saved-one"))
    }

    @Test
    fun `slash resume by index loads the session and repopulates the transcript`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("p")
        val saved = store.create(cwd, context.modelName, "saved-one")
        // A distant-future entry makes this the most-recent -> index 1 in the list.
        store.append(saved, UserEntry(Uuid.random(), null, Instant.parse("2999-01-01T00:00:00Z"), "remembered"))
        val current = store.create(cwd, context.modelName, null)
        val state = AppState()
        val agentLoop = loop(store, current)
        val controller = ConversationController(state, agentLoop)

        controller.submit("/resume 1")

        assertEquals(saved.id, agentLoop.session.id)
        assertTrue(agentLoop.history.any { it is UserEntry && it.text == "remembered" })
        assertTrue(state.messages.any { it.content == "remembered" })
        assertTrue(state.messages.last().content.contains("Resumed"))
    }

    @Test
    fun `unknown slash command falls through to the model`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val state = AppState()
        val controller = ConversationController(state, loop(store, session, InferenceResponse("answer", emptyList(), null)))

        controller.submit("/unknown please")

        // Treated as a normal prompt: user line + assistant answer.
        assertEquals(2, state.messages.size)
        assertEquals(MessageRole.User, state.messages[0].role)
        assertEquals("answer", state.messages[1].content)
    }

    @Test
    fun `slash compact summarizes older turns on demand`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val state = AppState()
        // Two turns build history (each assistant ~10 tokens), then a summary response for /compact.
        val agentLoop = AgentLoop(
            PromptProvider(
                FakeInferenceClient(
                    InferenceResponse("x".repeat(40), emptyList(), null),
                    InferenceResponse("x".repeat(40), emptyList(), null),
                    InferenceResponse("SUMMARY", emptyList(), null),
                ),
            ),
            NoToolExecutor,
            context,
            store,
            session,
            CompactionSettings(enabled = false, keepRecentTokens = 5),
        )
        val controller = ConversationController(state, agentLoop)
        controller.submit("first message")
        controller.submit("second message")

        controller.submit("/compact focus here")

        assertTrue(state.messages.last().content.contains("Compacted"))
        assertTrue(agentLoop.history.any { it is CompactionEntry })
    }
}
