package com.konductor.agent

import com.konductor.compaction.CompactionSettings
import com.konductor.compaction.TokenEstimator
import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceResponse
import com.konductor.session.JsonlSessionStore
import com.konductor.session.reconstructHistory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AgentLoopCompactionTest {
    private val context = AgentContext(systemPrompt = "sys", tools = emptyList(), modelName = "gpt-test")

    private fun big(tokens: Int) = "x".repeat(tokens * TokenEstimator.CHARS_PER_TOKEN)

    @Test
    fun `auto-compacts a later turn once reported usage exceeds the threshold`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        // Order consumed: turn1 (real) -> turn2 summarization -> turn2 (real).
        val fake = FakeInferenceClient(
            InferenceResponse(big(10), emptyList(), Usage(0, 0, 200)), // turn1: pushes context over threshold
            InferenceResponse("THE SUMMARY", emptyList(), null),        // turn2: compaction summarization
            InferenceResponse(big(10), emptyList(), Usage(0, 0, 150)),  // turn2: the real turn
        )
        val settings = CompactionSettings(enabled = true, contextWindow = 100, reserveTokens = 0, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context, store, session, settings)

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
        val session = store.create(root.resolve("p"), context.modelName, null)
        val fake = FakeInferenceClient(
            InferenceResponse(big(10), emptyList(), Usage(0, 0, 999_999)),
            InferenceResponse(big(10), emptyList(), Usage(0, 0, 999_999)),
        )
        // Default constructor compaction is disabled.
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context, store, session)

        runBlocking { loop.runTurn("u1").toList() }
        val events = runBlocking { loop.runTurn("u2").toList() }

        assertTrue(events.none { it is AgentEvent.Compacted })
        assertTrue(loop.history.none { it is CompactionEntry })
    }

    @Test
    fun `manual compact inserts the marker before kept entries and survives a reload`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val fake = FakeInferenceClient(
            InferenceResponse(big(10), emptyList(), null), // turn1
            InferenceResponse(big(10), emptyList(), null), // turn2
            InferenceResponse("MANUAL SUMMARY", emptyList(), null), // the /compact summarization
        )
        val settings = CompactionSettings(enabled = false, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context, store, session, settings)
        runBlocking { loop.runTurn("u1").toList() }
        runBlocking { loop.runTurn("u2").toList() }

        val entry = runBlocking { loop.compact("focus on the important bits") }

        assertNotNull(entry)
        // Reload from a fresh store: the on-disk order must match the in-memory [summarized, marker, kept] layout.
        val reloaded = JsonlSessionStore(root).load(session.id)
        val rebuilt = reconstructHistory(reloaded.entries)
        assertEquals("MANUAL SUMMARY", assertIs<CompactionEntry>(rebuilt.first()).summary)
        assertTrue(rebuilt.size > 1, "kept entries must follow the summary after a reload")
    }

    @Test
    fun `manual compact records estimated tokens before usage is reported`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), context.modelName, null)
        val ts = Instant.parse("2026-07-09T00:00:00Z")
        repeat(3) { i ->
            val user = UserEntry(Uuid.random(), null, ts, "question $i ${big(10)}")
            val assistant = AssistantEntry(Uuid.random(), user.id, ts, "answer $i ${big(10)}")
            session.entries += user
            store.append(session, user)
            session.entries += assistant
            store.append(session, assistant)
        }
        val fake = FakeInferenceClient(InferenceResponse("ESTIMATED SUMMARY", emptyList(), null))
        val settings = CompactionSettings(enabled = false, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context, store, session, settings)

        val entry = runBlocking { loop.compact() }

        assertNotNull(entry)
        assertTrue(entry.tokensBefore > 0, "manual compaction should estimate tokens before usage is reported")
    }

    @Test
    fun `switching sessions resets the tracker so a resumed session does not compact prematurely`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val a = store.create(root.resolve("p"), context.modelName, null)
        // Session B is pre-seeded with several turns (enough that it COULD be compacted).
        val b = store.create(root.resolve("p"), context.modelName, null)
        val ts = Instant.parse("2026-07-09T00:00:00Z")
        repeat(3) { i ->
            store.append(b, UserEntry(Uuid.random(), null, ts, "u$i"))
            store.append(b, AssistantEntry(Uuid.random(), null, ts, big(10)))
        }
        // Turn on A reports a huge usage; without a reset that stale size would carry into session B.
        val fake = FakeInferenceClient(
            InferenceResponse(big(10), emptyList(), Usage(0, 0, 999_999)),
            InferenceResponse(big(10), emptyList(), Usage(0, 0, 50)),
        )
        val settings = CompactionSettings(enabled = true, contextWindow = 100, reserveTokens = 0, keepRecentTokens = 5)
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context, store, a, settings)
        runBlocking { loop.runTurn("on A").toList() } // tracker climbs to ~999999 (well over the 100 threshold)

        loop.resume(b.id) // must reset the tracker to the resumed session

        val events = runBlocking { loop.runTurn("on B").toList() }
        // With the reset, the resumed session does not compact on its first turn. (Only two responses are queued;
        // a premature compaction would need a third for summarization and would fail the turn.)
        assertTrue(events.none { it is AgentEvent.Compacted })
        assertTrue(events.any { it is AgentEvent.TurnCompleted })
        assertTrue(events.none { it is AgentEvent.Failed })
    }
}
