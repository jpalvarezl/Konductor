package com.konductor.agent

import com.konductor.core.models.AgentContext
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentLoopTest {
    private val context = AgentContext(
        systemPrompt = "sys",
        tools = emptyList(),
        modelName = "gpt-test",
        temperature = null,
    )

    @Test
    fun `runTurn appends user and assistant entries and re-sends the full transcript`() {
        val fake = FakeInferenceClient(
            InferenceResponse("first answer", emptyList(), Usage(1, 2, 3)),
            InferenceResponse("second answer", emptyList(), Usage(4, 5, 9)),
        )
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context)

        val turn1 = runBlocking { loop.runTurn("hello").toList() }
        assertIs<AgentEvent.TurnCompleted>(turn1.last())
        assertEquals(2, loop.history.size)
        assertIs<UserEntry>(loop.history[0])
        assertIs<AssistantEntry>(loop.history[1])
        // The first request carried only the user entry.
        assertEquals(1, fake.requests[0].history.size)

        runBlocking { loop.runTurn("again").toList() }
        assertEquals(4, loop.history.size)
        // The second request re-sent the reconstructed transcript: user, assistant, user.
        assertEquals(3, fake.requests[1].history.size)
    }

    @Test
    fun `close delegates to the provider and inference client`() {
        val fake = FakeInferenceClient()
        val loop = AgentLoop(PromptProvider(fake), NoToolExecutor, context)

        runBlocking { loop.close() }

        assertTrue(fake.closed)
    }
}
