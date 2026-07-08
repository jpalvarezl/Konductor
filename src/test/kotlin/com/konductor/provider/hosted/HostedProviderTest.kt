package com.konductor.provider.hosted

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Entry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class HostedProviderTest {
    private val context = AgentContext(
        baseSystemPrompt = "server owns this",
        tools = emptyList(),
        modelName = "hosted",
    )
    private val noTools = ToolExecutor { call -> error("unexpected client-side tool ${call.name}") }

    @Test
    fun `sets up hosted session invokes latest user text and relays logs and completion`() {
        val usage = Usage(inputTokens = 7, outputTokens = 3, totalTokens = 10)
        val client = FakeHostedAgentClient(response = HostedAgentResponse("hosted answer", usage))
        val provider = HostedProvider(client, agentName = "agent-a", containerImage = "repo/image:tag")

        val events = runBlocking {
            provider.runTurn(TurnRequest(context, listOf(userEntry("hello hosted"))), noTools).toList()
        }

        assertEquals(AgentKind.Hosted, provider.kind)
        assertEquals(
            listOf("select:agent-a:repo/image:tag", "configure:agent-a:v1", "createSession:agent-a:v1"),
            client.calls.take(3),
        )
        assertEquals("invoke:agent-a:s1:hello hosted", client.calls.single { it.startsWith("invoke:") })
        assertTrue(events.any { it == AgentEvent.LogFrame("boot") })
        assertTrue(events.any { it == AgentEvent.TextDelta("hosted answer") })
        assertTrue(events.any { it == AgentEvent.UsageReported(usage) })
        val completed = assertIs<AgentEvent.TurnCompleted>(events.last())
        assertEquals("hosted answer", completed.assistant.text)
        assertEquals(usage, completed.assistant.usage)
    }

    @Test
    fun `reuses the server session across turns and cleans it up on close`() {
        val client = FakeHostedAgentClient(response = HostedAgentResponse("ok"))
        val provider = HostedProvider(client, agentName = "agent-a", containerImage = "repo/image:tag")

        runBlocking {
            provider.runTurn(TurnRequest(context, listOf(userEntry("one"))), noTools).toList()
            provider.runTurn(TurnRequest(context, listOf(userEntry("two"))), noTools).toList()
            provider.close()
        }

        assertEquals(1, client.calls.count { it.startsWith("createSession:") })
        assertTrue(client.calls.contains("invoke:agent-a:s1:one"))
        assertTrue(client.calls.contains("invoke:agent-a:s1:two"))
        assertTrue(client.calls.contains("delete:agent-a:s1"))
        assertTrue(client.calls.contains("close"))
    }

    @Test
    fun `uses an explicit hosted session reference when provided`() {
        val client = FakeHostedAgentClient(response = HostedAgentResponse("ok"))
        val provider = HostedProvider(client, agentName = "agent-a", containerImage = "repo/image:tag")

        runBlocking {
            provider.runTurn(
                TurnRequest(context, listOf(userEntry("resume")), sessionRef = "existing"),
                noTools,
            ).toList()
        }

        assertTrue(client.calls.contains("getSession:agent-a:existing"))
        assertTrue(client.calls.contains("invoke:agent-a:existing:resume"))
    }

    @Test
    fun `deletes the previously warm session when the session reference changes`() {
        val client = FakeHostedAgentClient(response = HostedAgentResponse("ok"))
        val provider = HostedProvider(client, agentName = "agent-a", containerImage = "repo/image:tag")

        runBlocking {
            // Turn 1 creates the warm session s1; turn 2 switches to an explicit different ref.
            provider.runTurn(TurnRequest(context, listOf(userEntry("one"))), noTools).toList()
            provider.runTurn(
                TurnRequest(context, listOf(userEntry("two")), sessionRef = "other"),
                noTools,
            ).toList()
        }

        // The abandoned s1 is deleted at switch time (not leaked), and the new session is used.
        assertTrue(client.calls.contains("delete:agent-a:s1"))
        assertTrue(client.calls.contains("getSession:agent-a:other"))
        assertTrue(client.calls.contains("invoke:agent-a:other:two"))
    }

    private fun userEntry(text: String): UserEntry =
        UserEntry(id = Uuid.random(), parentId = null, timestamp = Clock.System.now(), text = text)
}

private class FakeHostedAgentClient(
    private val response: HostedAgentResponse,
) : HostedAgentClient {
    val calls: MutableList<String> = mutableListOf()

    override suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion {
        calls += "select:$agentName:$containerImage"
        return HostedAgentVersion("v1")
    }

    override suspend fun configureResponsesEndpoint(agentName: String, version: String) {
        calls += "configure:$agentName:$version"
    }

    override suspend fun createSession(agentName: String, version: String): HostedAgentSession {
        calls += "createSession:$agentName:$version"
        return HostedAgentSession("s1")
    }

    override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession {
        calls += "getSession:$agentName:$sessionId"
        return HostedAgentSession(sessionId)
    }

    override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse {
        delay(10)
        calls += "invoke:$agentName:$sessionId:$input"
        return response
    }

    override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> = flow {
        calls += "logs:$agentName:$version:$sessionId"
        emit("boot")
    }

    override suspend fun stopSession(agentName: String, sessionId: String) {
        calls += "stop:$agentName:$sessionId"
    }

    override suspend fun deleteSession(agentName: String, sessionId: String) {
        calls += "delete:$agentName:$sessionId"
    }

    override suspend fun close() {
        calls += "close"
    }
}
