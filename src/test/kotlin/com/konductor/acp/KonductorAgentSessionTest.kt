package com.konductor.acp

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.core.models.AgentContext
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.PromptProvider
import com.konductor.provider.inference.FakeInferenceClient
import com.konductor.provider.inference.InferenceResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KonductorAgentSessionTest {
    private val context = AgentContext(
        baseSystemPrompt = "sys",
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
}
