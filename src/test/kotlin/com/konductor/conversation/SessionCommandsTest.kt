package com.konductor.conversation

import com.konductor.session.persistedCandidate
import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.compaction.CompactionSettings
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.Session
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.HostedSessionController
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.FoundryResponsesResult
import com.konductor.provider.inference.MockFoundryResponsesClient
import com.konductor.provider.inference.PreparedPromptAgentBinding
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import com.konductor.core.models.ToolSpec
import com.konductor.session.JsonlSessionStore
import com.konductor.session.SessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SessionCommandsTest {
    private val context = AgentContext(systemPrompt = "sys", tools = emptyList(), modelName = "gpt-test", temperature = null)

    private fun loop(store: JsonlSessionStore, session: Session, vararg responses: FoundryResponsesResult): AgentLoop =
        AgentLoop(PromptProvider(MockFoundryResponsesClient(*responses)), NoToolExecutor, context, store, session)

    private fun controller(state: AppState, agentLoop: AgentLoop): ConversationController =
        ConversationController(state, agentLoop, mockFoundryDiscovery(state, agentLoop))

    private fun managedController(
        state: AppState,
        store: SessionStore,
        session: Session,
        management: SessionCommandPromptManagement,
    ): Pair<AgentLoop, ConversationController> {
        val runtime = ProviderRuntime(
            PromptProvider(MockFoundryResponsesClient()),
            ProviderManagement.PromptAgents(management, management),
        )
        val loop = AgentLoop(runtime, NoToolExecutor, context, store, session)
        val command = PromptAgentCommand(
            state,
            { loop.context },
            { loop.activePromptAgentName },
            loop::bindPromptAgent,
            management,
        )
        return loop to ConversationController(state, loop, mockFoundryDiscovery(state, loop), command)
    }

    @Test
    fun `slash new starts a fresh session and clears the transcript`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val state = AppState(initialMessages = listOf(ChatMessage(MessageRole.User, "stale line")))
        val agentLoop = loop(store, session)
        val controller = controller(state, agentLoop)

        assertTrue(controller.submit("/new"))

        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.System, state.messages[0].role)
        assertTrue(state.messages[0].content.contains("new session"))
        assertNotEquals(session.id, agentLoop.session.id)
    }

    @Test
    fun `slash name renames and persists`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val state = AppState()
        val controller = controller(state, loop(store, session))

        controller.submit("/name My Work")

        assertEquals("My Work", session.name)
        assertEquals("My Work", JsonlSessionStore(root).load(session.id).name)
        assertTrue(state.messages.last().content.contains("My Work"))
    }

    @Test
    fun `slash name without an argument shows usage`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val state = AppState()
        controller(state, loop(store, session)).submit("/name")

        assertTrue(state.messages.last().content.contains("Usage: /name"))
    }

    @Test
    fun `slash session reports the active session without running a turn`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, "labelled")
        val state = AppState()
        // No queued responses: if a turn ran, the mock would throw. It must not.
        controller(state, loop(store, session)).submit("/session")

        assertEquals(1, state.messages.size)
        val info = state.messages[0].content
        assertTrue(info.contains(session.id.toString().take(8)))
        assertTrue(info.contains("labelled"))
    }

    @Test
    fun `slash resume with no saved sessions reports none`() {
        val state = AppState()
        // Default in-memory store: nothing is ever listed.
        val agentLoop = AgentLoop(PromptProvider(MockFoundryResponsesClient()), NoToolExecutor, context)
        val controller = controller(state, agentLoop)

        controller.submit("/resume")

        assertEquals(1, state.messages.size)
        assertTrue(state.messages[0].content.contains("No saved sessions"))
    }

    @Test
    fun `slash resume lists saved sessions`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("p")
        store.persistedCandidate(cwd, context.modelName, "saved-one")
        val current = store.persistedCandidate(cwd, context.modelName, null)
        val state = AppState()
        controller(state, loop(store, current)).submit("/resume")

        assertTrue(state.messages.last().content.contains("saved-one"))
    }

    @Test
    fun `slash resume by index loads the session and repopulates the transcript`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("p")
        val saved = store.persistedCandidate(cwd, context.modelName, "saved-one")
        // A distant-future entry makes this the most-recent -> index 1 in the list.
        store.append(saved, UserEntry(Uuid.random(), null, Instant.parse("2999-01-01T00:00:00Z"), "remembered"))
        val current = store.persistedCandidate(cwd, context.modelName, null)
        val state = AppState()
        val agentLoop = loop(store, current)
        val controller = controller(state, agentLoop)

        controller.submit("/resume 1")

        assertEquals(saved.id, agentLoop.session.id)
        assertTrue(agentLoop.history.any { it is UserEntry && it.text == "remembered" })
        assertTrue(state.messages.any { it.content == "remembered" })
        assertTrue(state.messages.last().content.contains("Resumed"))
    }

    @Test
    fun `slash new publishes current PromptAgent in first header without metadata repair`(@TempDir root: Path) {
        val durable = JsonlSessionStore(root.resolve("sessions"))
        val current = durable.persistedCandidate(root.resolve("workspace"), context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "billing")
            durable.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        var metadataCalls = 0
        val store = object : SessionStore by durable {
            override fun persistMetadata(session: Session, candidate: com.konductor.core.models.SessionMetadata) {
                metadataCalls++
                durable.persistMetadata(session, candidate)
            }
        }
        val management = SessionCommandPromptManagement("billing")
        val state = AppState(
            initialMessages = listOf(ChatMessage(MessageRole.System, "old transcript")),
            activeAgentName = "billing",
        )
        val (loop, controller) = managedController(state, store, current, management)

        controller.submit("/new")

        assertNotEquals(current.id, loop.session.id)
        assertEquals("billing", loop.session.promptAgentName)
        assertEquals("billing", JsonlSessionStore(root.resolve("sessions")).load(loop.session.id).promptAgentName)
        assertEquals(0, metadataCalls, "fresh publication must not patch metadata after persistNew")
        assertEquals("billing", state.activeAgentName)
    }

    @Test
    fun `slash new publication failure retains old session binding transcript and status`(@TempDir root: Path) {
        val durable = JsonlSessionStore(root.resolve("sessions"))
        val current = durable.persistedCandidate(root.resolve("workspace"), context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "old")
            durable.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        val store = object : SessionStore by durable {
            override fun persistNew(candidate: Session): Unit = error("publication rejected")
        }
        val management = SessionCommandPromptManagement("old")
        val state = AppState(
            initialMessages = listOf(ChatMessage(MessageRole.System, "old transcript")),
            activeAgentName = "old",
        )
        val (loop, controller) = managedController(state, store, current, management)

        controller.submit("/new")

        assertEquals(current.id, loop.session.id)
        assertEquals("old", management.activeAgent)
        assertEquals("old", state.activeAgentName)
        assertTrue(state.messages.any { it.content == "old transcript" })
        assertTrue(state.messages.last().content.contains("publication rejected"))
        assertEquals(listOf(current.id), durable.listForCwd(current.cwd).map { it.id })
    }

    @Test
    fun `resume prepares exact persisted name and omitted name unbinds without listing`(@TempDir root: Path) {
        val store = JsonlSessionStore(root.resolve("sessions"))
        val cwd = root.resolve("workspace")
        val named = store.persistedCandidate(cwd, context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "saved")
            store.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        val ephemeral = store.persistedCandidate(cwd, context.modelName, null)
        val current = store.persistedCandidate(cwd, context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "old")
            store.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        val management = SessionCommandPromptManagement("old")
        val state = AppState(activeAgentName = "old")
        val (loop, controller) = managedController(state, store, current, management)

        controller.submit("/resume ${named.id}")
        assertEquals(named.id, loop.session.id)
        assertEquals("saved", management.activeAgent)
        assertEquals("saved", state.activeAgentName)

        controller.submit("/resume ${ephemeral.id}")
        assertEquals(ephemeral.id, loop.session.id, state.messages.joinToString(" | ") { it.content })
        assertNull(management.activeAgent)
        assertNull(state.activeAgentName)
        assertEquals(listOf<String?>("saved", null), management.preparedNames)
        assertEquals(0, management.listCalls)
    }

    @Test
    fun `resume rejects whitespace-padded persisted PromptAgent without changing session binding or status`(
        @TempDir root: Path,
    ) {
        val store = JsonlSessionStore(root.resolve("sessions"))
        val cwd = root.resolve("workspace")
        val target = store.persistedCandidate(cwd, context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "saved")
            store.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        val targetFile = requireNotNull(store.locate(target))
        Files.writeString(
            targetFile,
            Files.readString(targetFile).replace(
                "\"promptAgentName\":\"saved\"",
                "\"promptAgentName\":\"  saved  \"",
            ),
        )
        val current = store.persistedCandidate(cwd, context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "old")
            store.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        val management = SessionCommandPromptManagement("old")
        val state = AppState(
            initialMessages = listOf(ChatMessage(MessageRole.System, "current transcript")),
            activeAgentName = "old",
        )
        val (loop, controller) = managedController(state, store, current, management)

        controller.submit("/resume ${target.id}")

        assertEquals(current.id, loop.session.id)
        assertEquals("old", loop.session.promptAgentName)
        assertEquals("old", management.activeAgent)
        assertEquals("old", state.activeAgentName)
        assertTrue(management.preparedNames.isEmpty())
        assertTrue(state.messages.any { it.content == "current transcript" })
        assertTrue(state.messages.last().content.contains("normalized"))
    }

    @Test
    fun `resume preparation failure retains previous transcript session binding and status`(@TempDir root: Path) {
        val store = JsonlSessionStore(root.resolve("sessions"))
        val cwd = root.resolve("workspace")
        val target = store.persistedCandidate(cwd, context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "broken")
            store.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        val current = store.persistedCandidate(cwd, context.modelName, null).also {
            val bound = it.metadata.copy(promptAgentName = "old")
            store.persistMetadata(it, bound)
            it.commitMetadata(bound)
        }
        val management = SessionCommandPromptManagement("old").also { it.failOnPrepare = "broken" }
        val state = AppState(
            initialMessages = listOf(ChatMessage(MessageRole.System, "current transcript")),
            activeAgentName = "old",
        )
        val (loop, controller) = managedController(state, store, current, management)

        controller.submit("/resume ${target.id}")

        assertEquals(current.id, loop.session.id)
        assertEquals("old", management.activeAgent)
        assertEquals("old", state.activeAgentName)
        assertTrue(state.messages.any { it.content == "current transcript" })
        assertTrue(state.messages.last().content.contains("cannot prepare broken"))
        assertEquals(0, management.listCalls)
    }

    @Test
    fun `Hosted resume failure retains active TUI session and transcript`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("hosted")
        val saved = store.persistedCandidate(cwd, "hosted", null)
        val savedBinding = HostedSessionBinding("hosted-agent", saved.id.toString())
        store.persistMetadata(saved, saved.metadata.copy(hostedBinding = savedBinding))
        saved.commitMetadata(saved.metadata.copy(hostedBinding = savedBinding))
        val current = store.newCandidate(cwd, "hosted", null).also { candidate ->
            candidate.hostedBinding = HostedSessionBinding("hosted-agent", candidate.id.toString())
            store.persistNew(candidate)
        }
        val provider = FailingHostedLifecycleProvider()
        val loop = AgentLoop(ProviderRuntime(provider), NoToolExecutor, context, store, current)
        runBlocking { loop.activateInitialSession(resuming = false) }
        val state = AppState(initialMessages = listOf(ChatMessage(MessageRole.System, "current transcript")))
        provider.failure = IllegalStateException("remote session is EXPIRED")

        ConversationController(state, loop, mockFoundryDiscovery(state, loop)).submit("/resume ${saved.id}")

        assertEquals(current.id, loop.session.id)
        assertTrue(state.messages.any { it.content == "current transcript" })
        assertTrue(state.messages.last().content.contains("EXPIRED"))
    }

    @Test
    fun resumesByUuid(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("p")
        val saved = store.persistedCandidate(cwd, context.modelName, "saved-by-id")
        val current = store.persistedCandidate(cwd, context.modelName, null)
        val agentLoop = loop(store, current)

        val state = AppState()
        controller(state, agentLoop).submit("/resume ${saved.id}")

        assertEquals(saved.id, agentLoop.session.id)
    }

    @Test
    fun `unknown slash command falls through to the model`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val state = AppState()
        val controller = controller(
            state,
            loop(store, session, FoundryResponsesResult("answer", emptyList(), null)),
        )

        controller.submit("/unknown please")

        // Treated as a normal prompt: user line + assistant answer.
        assertEquals(2, state.messages.size)
        assertEquals(MessageRole.User, state.messages[0].role)
        assertEquals("answer", state.messages[1].content)
    }

    @Test
    fun `slash compact summarizes older turns on demand`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val state = AppState()
        // Two turns build history (each assistant ~10 tokens), then a summary response for /compact.
        val responses = MockFoundryResponsesClient(
            FoundryResponsesResult("x".repeat(40), emptyList(), null),
            FoundryResponsesResult("x".repeat(40), emptyList(), null),
            FoundryResponsesResult("SUMMARY", emptyList(), null),
        )
        val agentLoop = AgentLoop(
            PromptProvider(responses),
            NoToolExecutor,
            context,
            store,
            session,
            CompactionSettings(enabled = false, keepRecentTokens = 5),
        )
        val controller = controller(state, agentLoop)
        controller.submit("first message")
        controller.submit("second message")

        controller.submit("/compact  Focus  HERE  ")

        assertTrue(state.messages.last().content.contains("Compacted"))
        assertTrue(agentLoop.history.any { it is CompactionEntry })
        val summaryPrompt = (responses.requests.last().history.single() as UserEntry).text
        assertTrue(summaryPrompt.contains("Extra focus for this summary: Focus  HERE"))
    }
}

private class SessionCommandPromptManagement(
    initialAgent: String?,
) : PromptAgentBinder, PromptAgentClient {
    override var activeAgent: String? = initialAgent
        private set
    val preparedNames = mutableListOf<String?>()
    var failOnPrepare: String? = null
    var listCalls: Int = 0

    override fun prepareBinding(agentName: String?): PreparedPromptAgentBinding {
        val normalized = agentName?.trim()?.ifBlank { null }
        preparedNames += normalized
        if (failOnPrepare != null && normalized == failOnPrepare) error("cannot prepare $normalized")
        return PreparedPromptAgentBinding(normalized, commitAction = { activeAgent = normalized })
    }

    override suspend fun listAgents(): List<String> {
        listCalls++
        return emptyList()
    }

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef = PromptAgentRef(name, "1")
}

private class FailingHostedLifecycleProvider : AgentProvider, HostedSessionController {
    override val hostedAgentName: String = "hosted-agent"
    override val kind: AgentKind = AgentKind.Hosted
    override val capabilities: ProviderCapabilities = ProviderCapabilities.Hosted
    var failure: RuntimeException? = null

    override suspend fun activate(binding: HostedSessionBinding?, hasLocalEntries: Boolean) {
        failure?.let { throw it }
    }

    override suspend fun detach() = Unit

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        emit(
            AgentEvent.TurnCompleted(
                AssistantEntry(Uuid.random(), null, Instant.parse("2026-01-01T00:00:00Z"), "ok"),
            ),
        )
    }

    override suspend fun close() = Unit
}
