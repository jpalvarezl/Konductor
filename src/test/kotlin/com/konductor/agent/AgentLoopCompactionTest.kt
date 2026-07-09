package com.konductor.agent

import com.konductor.compaction.CompactionSettings
import com.konductor.compaction.TokenEstimator
import com.konductor.core.models.AgentContext
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Usage
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
}
