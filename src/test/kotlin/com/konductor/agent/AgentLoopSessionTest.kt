package com.konductor.agent

import com.konductor.session.persistedCandidate
import com.konductor.compaction.CompactionSettings
import com.konductor.compaction.TokenEstimator
import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.Session
import com.konductor.core.models.SessionMetadata
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.HostedSessionController
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.konductor.provider.inference.FoundryResponsesEvent
import com.konductor.provider.inference.FoundryResponsesClient
import com.konductor.provider.inference.FoundryResponsesRequest
import com.konductor.provider.inference.FoundryResponsesResult
import com.konductor.provider.inference.MockFoundryResponsesClient
import com.konductor.provider.inference.PromptContextOverflowException
import com.konductor.core.models.Usage
import com.konductor.session.JsonlSessionStore
import com.konductor.session.SessionStore
import com.konductor.session.SessionSummary
import com.konductor.session.reconstructHistory
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AgentLoopSessionTest {
    private val context = AgentContext(systemPrompt = "sys", tools = emptyList(), modelName = "gpt-test", temperature = null)

    @Test
    fun `produced entries are persisted and survive a reload`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("proj"), context.modelName, null)
        val mock = MockFoundryResponsesClient(FoundryResponsesResult("hello answer", emptyList(), Usage(1, 2, 3)))
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, store, session)

        runBlocking { loop.runTurn("hi").toList() }

        val reloaded = JsonlSessionStore(root).load(session.id)
        assertEquals(2, reloaded.entries.size)
        assertEquals("hi", assertIs<UserEntry>(reloaded.entries[0]).text)
        assertEquals("hello answer", assertIs<AssistantEntry>(reloaded.entries[1]).text)
    }

    @Test
    fun `newSession retargets to a fresh session and leaves the old one on disk`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, "first")
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult("a", emptyList(), null),
            FoundryResponsesResult("b", emptyList(), null),
        )
        val loop = AgentLoop(PromptProvider(mock), NoToolExecutor, context, store, session)
        runBlocking { loop.runTurn("one").toList() }
        val firstId = loop.session.id

        val fresh = runBlocking { loop.newSession() }
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
        val first = store.persistedCandidate(cwd, context.modelName, null)
        store.append(first, UserEntry(Uuid.random(), null, Instant.parse("2026-07-08T10:00:00Z"), "remembered"))

        val current = store.persistedCandidate(cwd, context.modelName, null)
        val loop = AgentLoop(PromptProvider(MockFoundryResponsesClient()), NoToolExecutor, context, store, current)

        val resumed = runBlocking { loop.resume(first.id) }

        assertEquals(first.id, resumed.id)
        assertEquals(1, loop.history.size)
        assertEquals("remembered", assertIs<UserEntry>(loop.history[0]).text)
    }

    @Test
    fun `resume rejects a session from another canonical cwd without replacing active state`(@TempDir root: Path) {
        val store = JsonlSessionStore(root.resolve("sessions"))
        val workspaceA = java.nio.file.Files.createDirectory(root.resolve("a"))
        val workspaceB = java.nio.file.Files.createDirectory(root.resolve("b"))
        val current = store.persistedCandidate(workspaceA, context.modelName, null)
        val foreign = store.persistedCandidate(workspaceB, context.modelName, null)
        val loop = AgentLoop(PromptProvider(MockFoundryResponsesClient()), NoToolExecutor, context, store, current)

        val failure = assertFailsWith<IllegalArgumentException> { runBlocking { loop.resume(foreign.id) } }

        assertContains(failure.message.orEmpty(), workspaceA.toRealPath().toString())
        assertContains(failure.message.orEmpty(), workspaceB.toRealPath().toString())
        assertEquals(current.id, loop.session.id)
    }

    @Test
    fun `persisted Hosted binding is reserved before first activation and user append`(@TempDir root: Path) = runBlocking {
        val store = JsonlSessionStore(root)
        val session = persistedHostedCandidate(store, root.resolve("hosted"))
        val provider = RecordingHostedLifecycleProvider()
        provider.onActivate = { binding, hasEntries ->
            val reloaded = store.load(session.id)
            assertEquals(binding, reloaded.hostedBinding)
            assertTrue(reloaded.entries.isEmpty())
            assertFalse(hasEntries)
        }
        val loop = AgentLoop(ProviderRuntime(provider), NoToolExecutor, context, store, session)

        loop.activateInitialSession(resuming = false)
        assertEquals(session.id.toString(), store.load(session.id).hostedBinding?.sessionId)
        assertTrue(provider.events.isEmpty(), "new session must remain lazy until its first turn")

        loop.runTurn("first").toList()

        assertEquals("activate:${session.id}:false", provider.events.first())
        assertEquals("turn", provider.events[1])
        assertEquals(2, store.load(session.id).entries.size)
    }

    @Test
    fun `Hosted new detaches and resume reconnects exact binding transactionally`(@TempDir root: Path) = runBlocking {
        val store = JsonlSessionStore(root)
        val first = persistedHostedCandidate(store, root.resolve("hosted"))
        val provider = RecordingHostedLifecycleProvider()
        val loop = AgentLoop(ProviderRuntime(provider), NoToolExecutor, context, store, first)
        loop.activateInitialSession(resuming = false)
        loop.runTurn("one").toList()
        val firstBinding = loop.session.hostedBinding

        val fresh = loop.newSession()
        val freshBinding = fresh.hostedBinding
        assertNotEquals(firstBinding, freshBinding)
        assertEquals("detach", provider.events.last())
        assertEquals(firstBinding, store.load(first.id).hostedBinding)
        assertEquals(freshBinding, store.load(fresh.id).hostedBinding)

        loop.resume(first.id)
        assertEquals(first.id, loop.session.id)
        assertEquals("activate:${first.id}:true", provider.events.last())

        val prior = loop.session
        provider.activationFailure = IllegalStateException("remote terminal")
        val failedTarget = fresh.id
        assertFailsWith<IllegalStateException> { loop.resume(failedTarget) }
        assertEquals(prior.id, loop.session.id, "failed reconnect must retain the previously active local session")
    }

    @Test
    fun `Hosted new publication failure retains active session without detach`(@TempDir root: Path) = runBlocking {
        val durableStore = JsonlSessionStore(root)
        val current = persistedHostedCandidate(durableStore, root.resolve("hosted"))
        val failingStore = object : SessionStore by durableStore {
            override fun persistNew(candidate: Session): Unit = error("publication failed")
        }
        val provider = RecordingHostedLifecycleProvider()
        val loop = AgentLoop(ProviderRuntime(provider), NoToolExecutor, context, failingStore, current)
        loop.activateInitialSession(resuming = false)

        val failure = assertFailsWith<IllegalStateException> { loop.newSession() }

        assertEquals("publication failed", failure.message)
        assertEquals(current.id, loop.session.id)
        assertTrue(provider.events.isEmpty(), "publication failure must happen before detaching the active selection")
        assertEquals(listOf(current.id), durableStore.listForCwd(current.cwd).map { it.id })
    }

    @Test
    fun `non-empty pre-v2 session cannot be resumed as Hosted`(@TempDir root: Path) = runBlocking {
        val store = JsonlSessionStore(root)
        val legacy = store.persistedCandidate(root.resolve("hosted"), "hosted", null)
        store.append(legacy, UserEntry(Uuid.random(), null, Instant.parse("2026-07-08T10:00:00Z"), "old"))
        val current = persistedHostedCandidate(store, legacy.cwd)
        val provider = RecordingHostedLifecycleProvider()
        val loop = AgentLoop(ProviderRuntime(provider), NoToolExecutor, context, store, current)
        loop.activateInitialSession(resuming = false)

        val failure = assertFailsWith<IllegalArgumentException> { loop.resume(legacy.id) }

        assertContains(failure.message.orEmpty(), "Prompt v1 session")
        assertEquals(current.id, loop.session.id)
        assertTrue(provider.events.isEmpty())
    }

    @Test
    fun `rename persists the label`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val loop = AgentLoop(PromptProvider(MockFoundryResponsesClient()), NoToolExecutor, context, store, session)

        loop.rename("labeled")

        assertEquals("labeled", JsonlSessionStore(root).load(session.id).name)
    }

    @Test
    fun modelAndRenameCommitOnlyAfterPersistence(@TempDir root: Path) {
        val session = Session(
            Uuid.random(),
            "old-name",
            root,
            context.modelName,
            Instant.parse("2026-07-08T10:00:00Z"),
        )
        val observations = mutableListOf<Pair<SessionMetadata, SessionMetadata>>()
        val store = MockMetadataSessionStore { live, candidate -> observations += live.metadata to candidate }
        val loop = AgentLoop(PromptProvider(MockFoundryResponsesClient()), NoToolExecutor, context, store, session)

        assertIs<ModelSwitchResult.Switched>(loop.switchModel("new-model"))
        loop.rename("new-name")

        assertEquals(
            listOf(
                SessionMetadata("old-name", "gpt-test", null) to SessionMetadata("old-name", "new-model", null),
                SessionMetadata("old-name", "new-model", null) to SessionMetadata("new-name", "new-model", null),
            ),
            observations,
        )
        assertEquals(SessionMetadata("new-name", "new-model", null), session.metadata)
        assertEquals("new-model", loop.context.modelName)
    }

    @Test
    fun modelAndRenamePersistenceFailureKeepsLiveState(@TempDir root: Path) {
        val session = Session(
            Uuid.random(),
            "accepted",
            root,
            context.modelName,
            Instant.parse("2026-07-08T10:00:00Z"),
            promptAgentName = "accepted-agent",
        )
        val accepted = session.metadata
        val store = MockMetadataSessionStore { _, _ -> error("disk failure") }
        val loop = AgentLoop(PromptProvider(MockFoundryResponsesClient()), NoToolExecutor, context, store, session)

        assertIs<ModelSwitchResult.Invalid>(loop.switchModel("candidate-model"))
        assertEquals(accepted, session.metadata)
        assertEquals(context.modelName, loop.context.modelName)
        assertFailsWith<IllegalStateException> { loop.rename("candidate-name") }
        assertEquals(accepted, session.metadata)
    }

    @Test
    fun `a persistence failure fails the turn but does not crash out of the flow`(@TempDir root: Path) {
        // A store whose append() always throws (disk full / lock / permission). The failure must surface as a
        // recoverable AgentEvent.Failed, not propagate out of runTurn and take the app down.
        val failing = object : SessionStore {
            override fun newCandidate(cwd: Path, model: String, name: String?): Session =
                Session(Uuid.random(), name, cwd, model, Instant.parse("2026-07-08T10:00:00Z"))
            override fun persistNew(candidate: Session) = Unit
            override fun loadHeader(id: Uuid): com.konductor.core.models.SessionHeader =
                throw UnsupportedOperationException()
            override fun append(session: Session, entry: Entry): Unit = error("disk full")
            override fun load(id: Uuid): Session = throw UnsupportedOperationException()
            override fun listForCwd(cwd: Path): List<SessionSummary> = emptyList()
            override fun persistMetadata(session: Session, candidate: SessionMetadata) = Unit
            override fun rewrite(session: Session, candidateEntries: List<Entry>) = Unit
        }
        val session = failing.persistedCandidate(root, context.modelName, null)
        val loop = AgentLoop(
            PromptProvider(MockFoundryResponsesClient(FoundryResponsesResult("hi", emptyList(), null))),
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
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val mock = MockFoundryResponsesClient(
            FoundryResponsesResult("", listOf(ToolCall("c1", "read", "{}")), null),
            FoundryResponsesResult("done", emptyList(), null),
        )
        val executor = ToolExecutor { call -> ToolResult(call.callId, "body") }
        val loop = AgentLoop(PromptProvider(mock), executor, context, store, session)

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
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val responsesClient = PartialFailureThenSuccessFoundryResponsesClient()
        val loop = AgentLoop(PromptProvider(responsesClient), NoToolExecutor, context, store, session)

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
            responsesClient.requests[1].history.filterIsInstance<UserEntry>().map { it.text },
        )
        val afterRetry = store.load(session.id)
        assertEquals("recovered", assertIs<AssistantEntry>(afterRetry.entries.last()).text)
        assertTrue(afterRetry.entries.filterIsInstance<AssistantEntry>().none { it.text == "partial" })
    }

    @Test
    fun `cancelled partial stream keeps the user and cancellation stays transparent`(@TempDir root: Path) = runBlocking {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), context.modelName, null)
        val streamed = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val responsesClient = CancelThenSuccessFoundryResponsesClient(streamed, gate)
        val loop = AgentLoop(PromptProvider(responsesClient), NoToolExecutor, context, store, session)
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
            responsesClient.requests[1].history.filterIsInstance<UserEntry>().map { it.text },
        )
        val afterRetry = store.load(session.id)
        assertEquals("recovered", assertIs<AssistantEntry>(afterRetry.entries.last()).text)
        assertTrue(afterRetry.entries.filterIsInstance<AssistantEntry>().none { it.text == "partial" })
    }

    @Test
    fun recoveredTurnPersistsOneUserMarkerAndRetryAssistantInEventOrder(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = seededRecoverySession(store, root.resolve("recovered"))
        val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
        val client = TypedOverflowRecoveryClient(overflow, "SUMMARY", "retry answer")
        val loop = AgentLoop(
            PromptProvider(client),
            NoToolExecutor,
            context,
            store,
            session,
            CompactionSettings(enabled = false, keepRecentTokens = 5),
        )

        val events = runBlocking { loop.runTurn("accepted once").toList() }

        assertEquals(listOf("Compacted", "TextDelta", "TurnCompleted"), events.map { it::class.simpleName })
        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(1, restarted.entries.filterIsInstance<UserEntry>().count { it.text == "accepted once" })
        assertEquals(1, restarted.entries.filterIsInstance<CompactionEntry>().size)
        assertEquals("retry answer", restarted.entries.filterIsInstance<AssistantEntry>().last().text)
        assertEquals(client.requests[2].history, reconstructHistory(restarted.entries).dropLast(1))
    }

    @Test
    fun textOrCompletedUsageMakesOverflowIneligibleWithoutCompactionOrReplay(@TempDir root: Path) {
        listOf<AgentEvent>(AgentEvent.TextDelta(""), AgentEvent.UsageReported(Usage(10, 0, 10))).forEach { unsafe ->
            val store = JsonlSessionStore(root.resolve(unsafe::class.simpleName!!))
            val session = seededRecoverySession(store, root.resolve("unsafe"))
            val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
            val provider = SessionRecoveryProvider(listOf(listOf(unsafe, AgentEvent.Failed(overflow))))
            val loop = AgentLoop(
                provider,
                NoToolExecutor,
                context,
                store,
                session,
                CompactionSettings(enabled = false, keepRecentTokens = 5),
            )

            val events = runBlocking { loop.runTurn("unsafe").toList() }

            assertEquals(1, provider.requests.size)
            assertSame(overflow, events.filterIsInstance<AgentEvent.Failed>().single().error)
            assertTrue(events.none { it is AgentEvent.Compacted })
        }
    }

    @Test
    fun toolResultOverflowIsNotReplayedAndToolExecutesOnlyOnce(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = seededRecoverySession(store, root.resolve("tool-overflow"))
        val overflow = PromptContextOverflowException(IllegalStateException("overflow after tool"))
        val client = ToolThenOverflowClient(overflow)
        var executions = 0
        val executor = ToolExecutor { call ->
            executions++
            ToolResult(call.callId, "side effect complete")
        }
        val loop = AgentLoop(
            PromptProvider(client),
            executor,
            context,
            store,
            session,
            CompactionSettings(enabled = false, keepRecentTokens = 5),
        )

        val events = runBlocking { loop.runTurn("use tool").toList() }

        assertEquals(2, client.requests.size)
        assertEquals(1, executions)
        assertSame(overflow, events.filterIsInstance<AgentEvent.Failed>().single().error)
        assertTrue(events.none { it is AgentEvent.Compacted })
        assertEquals(1, store.load(session.id).entries.filterIsInstance<ToolResultEntry>().size)
    }

    @Test
    fun summaryFailureHasCompactionPrecedenceAndRetainsOriginalOverflowAsSuppressed(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = seededRecoverySession(store, root.resolve("summary-failure"))
        val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
        val summaryFailure = IllegalStateException("summary failed")
        val provider = SessionRecoveryProvider(
            listOf(
                listOf(AgentEvent.Failed(overflow)),
                listOf(AgentEvent.Failed(summaryFailure)),
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

        val events = runBlocking { loop.runTurn("accepted").toList() }

        assertEquals(2, provider.requests.size)
        val terminal = events.filterIsInstance<AgentEvent.Failed>().single().error
        val failure = assertIs<ContextOverflowRecoveryException>(terminal)
        assertEquals(ContextOverflowRecoveryStage.COMPACTION, failure.stage)
        assertSame(summaryFailure, failure.cause)
        assertEquals(listOf(overflow), failure.suppressed.toList())
        assertTrue(store.load(session.id).entries.none { it is CompactionEntry })
    }

    @Test
    fun reactiveRewriteFailureIsTerminalAndRestartKeepsAcceptedTranscript(@TempDir root: Path) {
        val durable = JsonlSessionStore(root)
        val session = seededRecoverySession(durable, root.resolve("rewrite-failure"))
        val store = object : SessionStore by durable {
            override fun rewrite(session: Session, candidateEntries: List<Entry>) = error("rewrite failed")
        }
        val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
        val provider = SessionRecoveryProvider(
            listOf(
                listOf(AgentEvent.Failed(overflow)),
                listOf(completedEvent("UNCOMMITTED")),
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

        val events = runBlocking { loop.runTurn("durable user").toList() }

        assertEquals(2, provider.requests.size)
        val terminal = events.filterIsInstance<AgentEvent.Failed>().single().error
        val failure = assertIs<ContextOverflowRecoveryException>(terminal)
        assertEquals(ContextOverflowRecoveryStage.COMPACTION, failure.stage)
        assertEquals("rewrite failed", failure.cause?.message)
        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(1, restarted.entries.filterIsInstance<UserEntry>().count { it.text == "durable user" })
        assertTrue(restarted.entries.none { it is CompactionEntry })
    }

    @Test
    fun retryAssistantPersistenceFailureIsRetryStageAndRetainsOverflow(@TempDir root: Path) {
        val durable = JsonlSessionStore(root)
        val session = seededRecoverySession(durable, root.resolve("retry-persistence-failure"))
        val persistenceFailure = IllegalStateException("retry append failed")
        val store = object : SessionStore by durable {
            override fun append(session: Session, entry: Entry) {
                if (entry is AssistantEntry && entry.text == "retry answer") throw persistenceFailure
                durable.append(session, entry)
            }
        }
        val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
        val provider = SessionRecoveryProvider(
            listOf(
                listOf(AgentEvent.Failed(overflow)),
                listOf(completedEvent("SUMMARY")),
                listOf(completedEvent("retry answer")),
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

        val events = runBlocking { loop.runTurn("accepted").toList() }

        assertEquals(3, provider.requests.size)
        val terminal = events.filterIsInstance<AgentEvent.Failed>().single().error
        val failure = assertIs<ContextOverflowRecoveryException>(terminal)
        assertEquals(ContextOverflowRecoveryStage.RETRY, failure.stage)
        assertSame(persistenceFailure, failure.cause)
        assertEquals(listOf(overflow), failure.suppressed.toList())
        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(1, restarted.entries.filterIsInstance<CompactionEntry>().size)
        assertTrue(loop.history.none { it is AssistantEntry && it.text == "retry answer" })
        assertTrue(reconstructHistory(restarted.entries).none { it is AssistantEntry && it.text == "retry answer" })
        assertEquals(reconstructHistory(restarted.entries), loop.history)
    }

    @Test
    fun retryAssistantPersistenceCancellationPropagatesRawWithoutFailedEvent(@TempDir root: Path) {
        val durable = JsonlSessionStore(root)
        val session = seededRecoverySession(durable, root.resolve("retry-persistence-cancellation"))
        val cancellation = CancellationException("retry append cancelled")
        val store = object : SessionStore by durable {
            override fun append(session: Session, entry: Entry) {
                if (entry is AssistantEntry && entry.text == "retry answer") throw cancellation
                durable.append(session, entry)
            }
        }
        val overflow = PromptContextOverflowException(IllegalStateException("overflow"))
        val provider = SessionRecoveryProvider(
            listOf(
                listOf(AgentEvent.Failed(overflow)),
                listOf(completedEvent("SUMMARY")),
                listOf(completedEvent("retry answer")),
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
        val events = mutableListOf<AgentEvent>()

        val terminal = assertFailsWith<CancellationException> {
            runBlocking { loop.runTurn("accepted").collect { events += it } }
        }

        assertSame(cancellation, terminal)
        assertEquals(3, provider.requests.size)
        assertTrue(events.any { it is AgentEvent.Compacted })
        assertTrue(events.none { it is AgentEvent.Failed })
        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(1, restarted.entries.filterIsInstance<CompactionEntry>().size)
        assertTrue(loop.history.none { it is AssistantEntry && it.text == "retry answer" })
        assertTrue(reconstructHistory(restarted.entries).none { it is AssistantEntry && it.text == "retry answer" })
        assertEquals(reconstructHistory(restarted.entries), loop.history)
    }

    private fun seededRecoverySession(store: JsonlSessionStore, cwd: Path): Session {
        val session = store.persistedCandidate(cwd, context.modelName, null)
        val timestamp = Instant.parse("2026-07-30T00:00:00Z")
        repeat(3) { index ->
            val user = UserEntry(
                Uuid.random(),
                session.entries.lastOrNull()?.id,
                timestamp,
                "question $index ${"x".repeat(10 * TokenEstimator.CHARS_PER_TOKEN)}",
            )
            session.entries += user
            store.append(session, user)
            val assistant = AssistantEntry(
                Uuid.random(),
                user.id,
                timestamp,
                "answer $index ${"x".repeat(10 * TokenEstimator.CHARS_PER_TOKEN)}",
            )
            session.entries += assistant
            store.append(session, assistant)
        }
        return session
    }

    private fun completedEvent(text: String) = AgentEvent.TurnCompleted(
        AssistantEntry(Uuid.random(), null, Clock.System.now(), text),
    )

    private fun persistedHostedCandidate(store: JsonlSessionStore, cwd: Path): Session {
        val candidate = store.newCandidate(cwd, "hosted", null)
        candidate.hostedBinding = HostedSessionBinding("hosted-agent", candidate.id.toString())
        store.persistNew(candidate)
        return candidate
    }
}

private class SessionRecoveryProvider(
    scripts: List<List<AgentEvent>>,
) : AgentProvider {
    private val scripts = ArrayDeque(scripts)
    val requests = mutableListOf<TurnRequest>()
    override val kind: AgentKind = AgentKind.Prompt
    override val capabilities: ProviderCapabilities = ProviderCapabilities.prompt(promptAgentManagement = false)

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        requests += request
        val events = scripts.removeFirstOrNull() ?: error("No script for provider call #${requests.size}")
        events.forEach { emit(it) }
    }

    override suspend fun close() = Unit
}

private class TypedOverflowRecoveryClient(
    private val overflow: PromptContextOverflowException,
    summary: String,
    answer: String,
) : FoundryResponsesClient {
    private val results = ArrayDeque(
        listOf(
            FoundryResponsesResult(summary, emptyList(), null),
            FoundryResponsesResult(answer, emptyList(), null),
        ),
    )
    val requests = mutableListOf<FoundryResponsesRequest>()

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
        requests += request
        if (requests.size == 1) throw overflow
        val result = results.removeFirstOrNull() ?: error("No result for request #${requests.size}")
        emit(FoundryResponsesEvent.TextDelta(result.text))
        emit(FoundryResponsesEvent.Completed(result))
    }

    override suspend fun close() = Unit
}

private class ToolThenOverflowClient(
    private val overflow: PromptContextOverflowException,
) : FoundryResponsesClient {
    val requests = mutableListOf<FoundryResponsesRequest>()

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
        requests += request
        if (requests.size == 1) {
            emit(
                FoundryResponsesEvent.Completed(
                    FoundryResponsesResult(
                        text = "",
                        toolCalls = listOf(ToolCall("call-1", "effect", "{}")),
                        usage = null,
                    ),
                ),
            )
        } else {
            throw overflow
        }
    }

    override suspend fun close() = Unit
}

private class RecordingHostedLifecycleProvider : AgentProvider, HostedSessionController {
    override val hostedAgentName: String = "hosted-agent"
    override val kind: AgentKind = AgentKind.Hosted
    override val capabilities: ProviderCapabilities = ProviderCapabilities.Hosted
    val events = mutableListOf<String>()
    var activationFailure: RuntimeException? = null
    var onActivate: ((com.konductor.core.models.HostedSessionBinding, Boolean) -> Unit)? = null

    override suspend fun activate(
        binding: com.konductor.core.models.HostedSessionBinding?,
        hasLocalEntries: Boolean,
    ) {
        activationFailure?.let { throw it }
        val durable = requireNotNull(binding)
        onActivate?.invoke(durable, hasLocalEntries)
        events += "activate:${durable.sessionId}:$hasLocalEntries"
    }

    override suspend fun detach() {
        events += "detach"
    }

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        events += "turn"
        emit(
            AgentEvent.TurnCompleted(
                AssistantEntry(Uuid.random(), null, Instant.parse("2026-07-08T10:00:01Z"), "ok"),
            ),
        )
    }

    override suspend fun close() = Unit
}

private class MockMetadataSessionStore(
    private val onPersist: (Session, SessionMetadata) -> Unit,
) : SessionStore {
    override fun newCandidate(cwd: Path, model: String, name: String?): Session =
        Session(Uuid.random(), name, cwd, model, Instant.parse("2026-07-08T10:00:00Z"))

    override fun persistNew(candidate: Session) = Unit

    override fun loadHeader(id: Uuid): com.konductor.core.models.SessionHeader = throw UnsupportedOperationException()

    override fun append(session: Session, entry: Entry) = Unit

    override fun load(id: Uuid): Session = throw UnsupportedOperationException()

    override fun listForCwd(cwd: Path): List<SessionSummary> = emptyList()

    override fun persistMetadata(session: Session, candidate: SessionMetadata) = onPersist(session, candidate)

    override fun rewrite(session: Session, candidateEntries: List<Entry>) = Unit
}

private class PartialFailureThenSuccessFoundryResponsesClient : FoundryResponsesClient {
    val requests = mutableListOf<FoundryResponsesRequest>()

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
        requests += request
        if (requests.size == 1) {
            emit(FoundryResponsesEvent.TextDelta("partial"))
            error("stream failed")
        }
        emit(FoundryResponsesEvent.Completed(FoundryResponsesResult("recovered", emptyList(), null)))
    }

    override suspend fun close() = Unit
}

private class CancelThenSuccessFoundryResponsesClient(
    private val streamed: CompletableDeferred<Unit>,
    private val gate: CompletableDeferred<Unit>,
) : FoundryResponsesClient {
    val requests = mutableListOf<FoundryResponsesRequest>()

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
        requests += request
        if (requests.size == 1) {
            emit(FoundryResponsesEvent.TextDelta("partial"))
            streamed.complete(Unit)
            gate.await()
            emit(FoundryResponsesEvent.Completed(FoundryResponsesResult("late", emptyList(), null)))
        } else {
            emit(FoundryResponsesEvent.Completed(FoundryResponsesResult("recovered", emptyList(), null)))
        }
    }

    override suspend fun close() = Unit
}
