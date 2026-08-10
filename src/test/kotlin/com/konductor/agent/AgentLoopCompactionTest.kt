package com.konductor.agent

import com.konductor.session.persistedCandidate
import com.konductor.compaction.CompactionSettings
import com.konductor.compaction.TokenEstimator
import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.SessionHistoryOwnership
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.FoundryResponsesResult
import com.konductor.provider.inference.MockFoundryResponsesClient
import com.konductor.provider.inference.PromptContextOverflowException
import com.konductor.session.JsonlSessionStore
import com.konductor.session.SessionStore
import com.konductor.session.reconstructHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AgentLoopCompactionTest {
    private val context = AgentContext(systemPrompt = "sys", tools = emptyList(), modelName = "gpt-test")

    private fun big(tokens: Int) = "x".repeat(tokens * TokenEstimator.CHARS_PER_TOKEN)

    @Test
    fun `auto-compacts a later turn once reported usage exceeds the threshold`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        // Order consumed: turn1 (real) -> turn2 summarization -> turn2 (real).
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult(big(10), emptyList(), Usage(0, 0, 200)), // turn1: pushes context over threshold
            FoundryResponsesResult("THE SUMMARY", emptyList(), null),        // turn2: compaction summarization
            FoundryResponsesResult(big(10), emptyList(), Usage(0, 0, 150)),  // turn2: the real turn
        )
        val settings = CompactionSettings(enabled = true, contextWindow = 100, reserveTokens = 0, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, store, session, settings)

        runBlocking { loop.runTurn("u1").toList() }               // no compaction yet (tracker starts at 0)
        val events = runBlocking { loop.runTurn("u2").toList() }  // now over threshold -> compaction

        val compacted = events.filterIsInstance<AgentEvent.Compacted>().singleOrNull()
        assertNotNull(compacted, "expected a Compacted event on the second turn")
        assertEquals("THE SUMMARY", compacted.entry.summary)
        // The reconstructed transcript now leads with the summary marker.
        assertIs<CompactionEntry>(loop.history.first())
    }

    @Test
    fun `does not auto-compact when disabled even with high reported usage`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult(big(10), emptyList(), Usage(0, 0, 999_999)),
            FoundryResponsesResult(big(10), emptyList(), Usage(0, 0, 999_999)),
        )
        // Default constructor compaction is disabled.
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, store, session)

        runBlocking { loop.runTurn("u1").toList() }
        val events = runBlocking { loop.runTurn("u2").toList() }

        assertTrue(events.none { it is AgentEvent.Compacted })
        assertTrue(loop.history.none { it is CompactionEntry })
    }

    @Test
    fun `Hosted runtime never auto-compacts and receives only the current user entry`(@TempDir root: Path) {
        var turns = 0
        val receivedHistory = mutableListOf<List<com.konductor.core.models.Entry>>()
        val provider = object : AgentProvider {
            override val kind: AgentKind = AgentKind.Hosted
            override val capabilities: ProviderCapabilities = ProviderCapabilities.Hosted

            override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
                turns++
                receivedHistory += request.history
                emit(AgentEvent.UsageReported(Usage(100, 1, 101)))
                emit(
                    AgentEvent.TurnCompleted(
                        AssistantEntry(Uuid.random(), null, Clock.System.now(), "hosted answer"),
                    ),
                )
            }

            override suspend fun close() = Unit
        }
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val settings = CompactionSettings(enabled = true, contextWindow = 1, reserveTokens = 0, keepRecentTokens = 0)
        val loop = AgentLoop(provider, NoToolExecutor, context, store, session, settings)

        runBlocking { loop.runTurn("one").toList() }
        val second = runBlocking { loop.runTurn("two").toList() }

        assertEquals(2, turns, "a third provider call would be an accidental compaction summarization turn")
        assertTrue(second.none { it is AgentEvent.Compacted })
        assertTrue(receivedHistory.all { history -> history.single() is UserEntry })
        assertEquals(listOf("one", "two"), receivedHistory.map { (it.single() as UserEntry).text })
        assertEquals(CompactionResult.Unsupported, runBlocking { loop.compact() })
        assertEquals(ModelSwitchResult.Unsupported, loop.switchModel("ignored"))
        assertEquals("gpt-test", loop.modelName)
    }

    @Test
    fun `manual compact is rejected while a turn is active`(@TempDir root: Path) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("active-turn"), context.modelName, null)
        val loop = AgentLoop(GatedCompactionProvider(started, release), NoToolExecutor, context, store, session)
        val activeTurn = async { loop.runTurn("active").toList() }
        started.await()

        val overlap = runCatching { loop.compact() }.exceptionOrNull()

        release.complete(Unit)
        activeTurn.await()
        assertIs<TurnAlreadyInProgressException>(overlap)
        assertEquals(listOf("active"), session.entries.filterIsInstance<UserEntry>().map { it.text })
        assertTrue(session.entries.none { it is CompactionEntry })
    }

    @Test
    fun `active manual compact rejects a turn and another manual compact`(@TempDir root: Path) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("active-compact"), context.modelName, null)
        val ts = Instant.parse("2026-07-09T00:00:00Z")
        repeat(3) { i ->
            val user = UserEntry(Uuid.random(), null, ts, "question $i ${big(10)}")
            val assistant = AssistantEntry(Uuid.random(), user.id, ts, "answer $i ${big(10)}")
            session.entries += user
            store.append(session, user)
            session.entries += assistant
            store.append(session, assistant)
        }
        val before = session.entries.map { it.id }
        val settings = CompactionSettings(enabled = false, keepRecentTokens = 5)
        val loop = AgentLoop(
            GatedCompactionProvider(started, release),
            NoToolExecutor,
            context,
            store,
            session,
            settings,
        )
        val activeCompact = async { loop.compact() }
        started.await()

        val turnOverlap = runCatching { loop.runTurn("must not be recorded").toList() }.exceptionOrNull()
        val compactOverlap = runCatching { loop.compact() }.exceptionOrNull()

        release.complete(Unit)
        val completed = activeCompact.await()
        assertIs<TurnAlreadyInProgressException>(turnOverlap)
        assertIs<TurnAlreadyInProgressException>(compactOverlap)
        assertEquals(before, session.entries.filterNot { it is CompactionEntry }.map { it.id })
        assertNotNull(assertIs<CompactionResult.Completed>(completed).entry)
        Unit
    }

    @Test
    fun `manual compact inserts the marker before kept entries and survives a reload`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult(big(10), emptyList(), null), // turn1
            FoundryResponsesResult(big(10), emptyList(), null), // turn2
            FoundryResponsesResult("MANUAL SUMMARY", emptyList(), null), // the /compact summarization
        )
        val settings = CompactionSettings(enabled = false, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, store, session, settings)
        runBlocking { loop.runTurn("u1").toList() }
        runBlocking { loop.runTurn("u2").toList() }

        val result = runBlocking { loop.compact("focus on the important bits") }
        val entry = assertIs<CompactionResult.Completed>(result).entry

        assertNotNull(entry)
        // Reload from a fresh store: the on-disk order must match the in-memory [summarized, marker, kept] layout.
        val reloaded = JsonlSessionStore(root).load(session.id)
        val rebuilt = reconstructHistory(reloaded.entries)
        assertEquals("MANUAL SUMMARY", assertIs<CompactionEntry>(rebuilt.first()).summary)
        assertTrue(rebuilt.size > 1, "kept entries must follow the summary after a reload")
    }

    @Test
    fun `failed compaction rewrite leaves live and restart ordering unchanged`(@TempDir root: Path) {
        val durableStore = JsonlSessionStore(root)
        val session = durableStore.persistedCandidate(root.resolve("p"), context.modelName, null)
        val failingStore = object : SessionStore by durableStore {
            override fun rewrite(session: Session, candidateEntries: List<Entry>) {
                error("rewrite failed")
            }
        }
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult(big(10), emptyList(), null),
            FoundryResponsesResult(big(10), emptyList(), null),
            FoundryResponsesResult("UNCOMMITTED SUMMARY", emptyList(), null),
            FoundryResponsesResult("turn after failed compact", emptyList(), null),
        )
        val settings = CompactionSettings(enabled = false, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, failingStore, session, settings)
        runBlocking { loop.runTurn("u1").toList() }
        runBlocking { loop.runTurn("u2").toList() }
        val acceptedIds = session.entries.map { it.id }

        val failure = assertFailsWith<IllegalStateException> { runBlocking { loop.compact() } }

        assertEquals("rewrite failed", failure.message)
        assertEquals(acceptedIds, session.entries.map { it.id })
        assertTrue(session.entries.none { it is CompactionEntry })
        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(acceptedIds, restarted.entries.map { it.id })
        assertTrue(restarted.entries.none { it is CompactionEntry })

        val nextTurn = runBlocking { loop.runTurn("after failure").toList() }
        assertTrue(nextTurn.any { it is AgentEvent.TurnCompleted }, "failed compaction must release the operation lock")
    }

    @Test
    fun `manual compact records estimated tokens before usage is reported`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val ts = Instant.parse("2026-07-09T00:00:00Z")
        repeat(3) { i ->
            val user = UserEntry(Uuid.random(), null, ts, "question $i ${big(10)}")
            val assistant = AssistantEntry(Uuid.random(), user.id, ts, "answer $i ${big(10)}")
            session.entries += user
            store.append(session, user)
            session.entries += assistant
            store.append(session, assistant)
        }
        val mock = MockFoundryResponsesClient(FoundryResponsesResult("ESTIMATED SUMMARY", emptyList(), null))
        val settings = CompactionSettings(enabled = false, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, store, session, settings)

        val result = runBlocking { loop.compact() }
        val entry = assertIs<CompactionResult.Completed>(result).entry

        assertNotNull(entry)
        assertTrue(entry.tokensBefore > 0, "manual compaction should estimate tokens before usage is reported")
    }

    @Test
    fun `eligible overflow compacts and retries once even when auto compaction is disabled`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = seededSession(store, root.resolve("reactive"))
        val overflow = PromptContextOverflowException(IllegalStateException("service overflow"))
        val provider = ScriptedRecoveryProvider(
            listOf(
                listOf(AgentEvent.Failed(overflow)),
                listOf(completed("REACTIVE SUMMARY")),
                listOf(completed("recovered")),
            ),
        )
        val settings = CompactionSettings(enabled = false, keepRecentTokens = 5)
        val loop = AgentLoop(provider, NoToolExecutor, context, store, session, settings)

        val events = runBlocking { loop.runTurn("same accepted user").toList() }

        assertEquals(3, provider.requests.size)
        assertEquals(1, session.entries.filterIsInstance<UserEntry>().count { it.text == "same accepted user" })
        assertEquals(1, events.filterIsInstance<AgentEvent.Compacted>().size)
        assertEquals("recovered", events.filterIsInstance<AgentEvent.TurnCompleted>().single().assistant.text)
        assertTrue(events.none { it is AgentEvent.Failed })
        assertIs<CompactionEntry>(provider.requests[2].history.first())
        assertEquals(
            1,
            provider.requests[2].history.filterIsInstance<UserEntry>().count { it.text == "same accepted user" },
        )
    }

    @Test
    fun `overflow with no compactable history terminates without retry`() {
        val overflow = PromptContextOverflowException(IllegalStateException("service overflow"))
        val provider = ScriptedRecoveryProvider(listOf(listOf(AgentEvent.Failed(overflow))))
        val loop = AgentLoop(
            provider,
            NoToolExecutor,
            context,
            compaction = CompactionSettings(enabled = false, keepRecentTokens = 20),
        )

        val events = runBlocking { loop.runTurn("short").toList() }

        assertEquals(1, provider.requests.size)
        val terminal = events.filterIsInstance<AgentEvent.Failed>().single().error
        val failure = assertIs<ContextOverflowRecoveryException>(terminal)
        assertEquals(ContextOverflowRecoveryStage.NO_COMPACTABLE_HISTORY, failure.stage)
        assertSame(overflow, failure.cause)
    }

    @Test
    fun `second overflow is a retry-stage failure and cannot start another cycle`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = seededSession(store, root.resolve("second-overflow"))
        val first = PromptContextOverflowException(IllegalStateException("first"))
        val second = PromptContextOverflowException(IllegalStateException("second"))
        val provider = ScriptedRecoveryProvider(
            listOf(
                listOf(AgentEvent.Failed(first)),
                listOf(completed("SUMMARY")),
                listOf(AgentEvent.Failed(second)),
            ),
        )
        val loop = AgentLoop(
            provider,
            NoToolExecutor,
            context,
            store,
            session,
            CompactionSettings(enabled = false, keepRecentTokens = 5),
        )

        val events = runBlocking { loop.runTurn("new").toList() }

        assertEquals(3, provider.requests.size)
        assertEquals(1, events.filterIsInstance<AgentEvent.Compacted>().size)
        val terminal = events.filterIsInstance<AgentEvent.Failed>().single().error
        val failure = assertIs<ContextOverflowRecoveryException>(terminal)
        assertEquals(ContextOverflowRecoveryStage.RETRY, failure.stage)
        assertSame(second, failure.cause)
        assertEquals(listOf(first), failure.suppressed.toList())
    }

    @Test
    fun `Hosted and server-owned history never recover a typed overflow`() {
        listOf(
            ProviderCapabilities.Hosted,
            ProviderCapabilities(
                clientCompaction = true,
                clientModelSwitching = false,
                localTools = false,
                promptAgentManagement = false,
                sessionHistoryOwnership = SessionHistoryOwnership.Server,
            ),
        ).forEach { capabilities ->
            val overflow = PromptContextOverflowException(IllegalStateException("faulty provider"))
            val provider = ScriptedRecoveryProvider(listOf(listOf(AgentEvent.Failed(overflow))), capabilities)
            val loop = AgentLoop(provider, NoToolExecutor, context)

            val events = runBlocking { loop.runTurn("hosted").toList() }

            assertEquals(1, provider.requests.size)
            assertSame(overflow, events.filterIsInstance<AgentEvent.Failed>().single().error)
            assertTrue(events.none { it is AgentEvent.Compacted })
        }
    }

    @Test
    fun `proactive success does not consume reactive budget`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = seededSession(store, root.resolve("proactive-reactive"))
        val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
        val provider = ScriptedRecoveryProvider(
            listOf(
                listOf(AgentEvent.UsageReported(Usage(0, 0, 1_000)), completed("prime")),
                listOf(completed("PROACTIVE SUMMARY")),
                listOf(AgentEvent.Failed(overflow)),
                listOf(completed("REACTIVE SUMMARY")),
                listOf(completed("recovered")),
            ),
        )
        val settings = CompactionSettings(
            enabled = true,
            contextWindow = 100,
            reserveTokens = 0,
            keepRecentTokens = 15,
        )
        val loop = AgentLoop(provider, NoToolExecutor, context, store, session, settings)
        runBlocking { loop.runTurn("prime tracker").toList() }

        val events = runBlocking { loop.runTurn("overflowing turn").toList() }

        assertEquals(5, provider.requests.size)
        assertEquals(2, events.filterIsInstance<AgentEvent.Compacted>().size)
        assertEquals("recovered", events.filterIsInstance<AgentEvent.TurnCompleted>().single().assistant.text)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test
    fun `proactive summary failure remains best effort and reactive recovery can proceed`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = seededSession(store, root.resolve("proactive-summary-failure"))
        val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
        val provider = ScriptedRecoveryProvider(
            listOf(
                listOf(AgentEvent.UsageReported(Usage(0, 0, 1_000)), completed("prime")),
                listOf(AgentEvent.Failed(IllegalStateException("proactive summary failed"))),
                listOf(AgentEvent.Failed(overflow)),
                listOf(completed("REACTIVE SUMMARY")),
                listOf(completed("recovered")),
            ),
        )
        val settings = CompactionSettings(enabled = true, contextWindow = 100, reserveTokens = 0, keepRecentTokens = 5)
        val loop = AgentLoop(provider, NoToolExecutor, context, store, session, settings)
        runBlocking { loop.runTurn("prime tracker").toList() }

        val events = runBlocking { loop.runTurn("overflowing turn").toList() }

        assertEquals(5, provider.requests.size)
        assertEquals(1, events.filterIsInstance<AgentEvent.Compacted>().size)
        assertEquals("recovered", events.filterIsInstance<AgentEvent.TurnCompleted>().single().assistant.text)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test
    fun `proactive rewrite failure is terminal before main provider call`(@TempDir root: Path) {
        val durable = JsonlSessionStore(root)
        val session = seededSession(durable, root.resolve("proactive-rewrite-failure"))
        val store = object : SessionStore by durable {
            override fun rewrite(session: Session, candidateEntries: List<Entry>) = error("proactive rewrite failed")
        }
        val provider = ScriptedRecoveryProvider(
            listOf(
                listOf(AgentEvent.UsageReported(Usage(0, 0, 1_000)), completed("prime")),
                listOf(completed("PROACTIVE SUMMARY")),
            ),
        )
        val settings = CompactionSettings(enabled = true, contextWindow = 100, reserveTokens = 0, keepRecentTokens = 5)
        val loop = AgentLoop(provider, NoToolExecutor, context, store, session, settings)
        runBlocking { loop.runTurn("prime tracker").toList() }

        val events = runBlocking { loop.runTurn("accepted before rewrite").toList() }

        assertEquals(2, provider.requests.size, "the main request must not start after rewrite failure")
        assertEquals("proactive rewrite failed", events.filterIsInstance<AgentEvent.Failed>().single().error.message)
        assertTrue(events.none { it is AgentEvent.Compacted })
        assertEquals(
            1,
            durable.load(session.id).entries
                .filterIsInstance<UserEntry>()
                .count { it.text == "accepted before rewrite" },
        )
    }

    @Test
    fun `switching sessions resets the tracker so a resumed session does not compact prematurely`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val a = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        // Session B is pre-seeded with several turns (enough that it COULD be compacted).
        val b = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val ts = Instant.parse("2026-07-09T00:00:00Z")
        repeat(3) { i ->
            store.append(b, UserEntry(Uuid.random(), null, ts, "u$i"))
            store.append(b, AssistantEntry(Uuid.random(), null, ts, big(10)))
        }
        // Turn on A reports a huge usage; without a reset that stale size would carry into session B.
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult(big(10), emptyList(), Usage(0, 0, 999_999)),
            FoundryResponsesResult(big(10), emptyList(), Usage(0, 0, 50)),
        )
        val settings = CompactionSettings(enabled = true, contextWindow = 100, reserveTokens = 0, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, store, a, settings)
        runBlocking { loop.runTurn("on A").toList() } // tracker climbs to ~999999 (well over the 100 threshold)

        runBlocking { loop.resume(b.id) } // must reset the tracker to the resumed session

        val events = runBlocking { loop.runTurn("on B").toList() }
        // With the reset, the resumed session does not compact on its first turn. (Only two responses are queued;
        // a premature compaction would need a third for summarization and would fail the turn.)
        assertTrue(events.none { it is AgentEvent.Compacted })
        assertTrue(events.any { it is AgentEvent.TurnCompleted })
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    private fun seededSession(store: JsonlSessionStore, cwd: Path): Session {
        val session = store.persistedCandidate(cwd, context.modelName, null)
        val timestamp = Instant.parse("2026-07-30T00:00:00Z")
        repeat(3) { index ->
            val user = UserEntry(Uuid.random(), session.entries.lastOrNull()?.id, timestamp, "u$index ${big(10)}")
            session.entries += user
            store.append(session, user)
            val assistant = AssistantEntry(
                Uuid.random(),
                user.id,
                timestamp,
                "a$index ${big(10)}",
            )
            session.entries += assistant
            store.append(session, assistant)
        }
        return session
    }

    private fun completed(text: String): AgentEvent.TurnCompleted = AgentEvent.TurnCompleted(
        AssistantEntry(Uuid.random(), null, Clock.System.now(), text),
    )
}

private class ScriptedRecoveryProvider(
    scripts: List<List<AgentEvent>>,
    override val capabilities: ProviderCapabilities = ProviderCapabilities.prompt(promptAgentManagement = false),
) : AgentProvider {
    private val scripts = ArrayDeque(scripts)
    val requests = mutableListOf<TurnRequest>()
    override val kind: AgentKind = if (capabilities.sessionHistoryOwnership == SessionHistoryOwnership.Server) {
        AgentKind.Hosted
    } else {
        AgentKind.Prompt
    }

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        requests += request
        val events = scripts.removeFirstOrNull() ?: error("No script for provider call #${requests.size}")
        events.forEach { emit(it) }
    }

    override suspend fun close() = Unit
}

private class GatedCompactionProvider(
    private val started: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
) : AgentProvider {
    override val kind: AgentKind = AgentKind.Prompt
    override val capabilities: ProviderCapabilities = ProviderCapabilities.prompt(promptAgentManagement = false)

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        started.complete(Unit)
        release.await()
        emit(
            AgentEvent.TurnCompleted(
                AssistantEntry(Uuid.random(), null, Clock.System.now(), "GATED SUMMARY"),
            ),
        )
    }

    override suspend fun close() = Unit
}
