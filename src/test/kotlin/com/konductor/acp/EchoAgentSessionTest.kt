package com.konductor.acp

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class EchoAgentSessionTest {
    @Test
    fun `prompt streams an echo chunk then ends the turn`() = runBlocking {
        val session = EchoAgentSession(SessionId("test-session"))

        val events = session.prompt(listOf(ContentBlock.Text("hello world")), _meta = null).toList()

        assertEquals(2, events.size)

        val chunk = (events[0] as Event.SessionUpdateEvent).update as SessionUpdate.AgentMessageChunk
        assertEquals("Echo: hello world", (chunk.content as ContentBlock.Text).text)

        val response = (events[1] as Event.PromptResponseEvent).response
        assertEquals(StopReason.END_TURN, response.stopReason)
    }

    @Test
    fun `prompt joins multiple text blocks with newlines`() = runBlocking {
        val session = EchoAgentSession(SessionId("test-session"))

        val events = session.prompt(
            listOf(ContentBlock.Text("line one"), ContentBlock.Text("line two")),
            _meta = null,
        ).toList()

        val chunk = (events[0] as Event.SessionUpdateEvent).update as SessionUpdate.AgentMessageChunk
        assertEquals("Echo: line one\nline two", (chunk.content as ContentBlock.Text).text)
    }

    @Test
    fun `prompt without text blocks still ends the turn`() = runBlocking {
        val session = EchoAgentSession(SessionId("test-session"))

        val events = session.prompt(emptyList(), _meta = null).toList()

        assertEquals(2, events.size)
        val chunk = (events[0] as Event.SessionUpdateEvent).update as SessionUpdate.AgentMessageChunk
        assertEquals("Echo: ", (chunk.content as ContentBlock.Text).text)
        assertEquals(StopReason.END_TURN, (events[1] as Event.PromptResponseEvent).response.stopReason)
    }
}
