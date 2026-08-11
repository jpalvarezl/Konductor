package com.konductor.provider.hosted

import com.konductor.core.models.AgentContext
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class HostedProviderTest {
    private val context = AgentContext("server owns this", emptyList(), "hosted")
    private val noTools = ToolExecutor { call -> error("unexpected client-side tool ${call.name}") }

    @Test
    fun `empty durable binding creates exact id lazily reuses it and close only detaches`() = runBlocking {
        val id = Uuid.random().toString()
        val client = FakeHostedAgentClient()
        client.getResults += HostedSessionNotFoundException("missing")
        client.deterministicCreate = session(id)
        val provider = provider(client)

        provider.activate(binding(id), hasLocalEntries = false)
        val events = provider.runTurn(turn("one"), noTools).toList()
        provider.activate(binding(id), hasLocalEntries = true)
        provider.runTurn(turn("two"), noTools).toList()
        provider.close()

        assertEquals(AgentKind.Hosted, provider.kind)
        assertContains(client.calls, "create:agent-a:v1:$id")
        assertEquals(1, client.calls.count { it.startsWith("create:") })
        assertEquals(listOf(id, id), client.invokedSessionIds)
        assertTrue(client.deletedSessionIds.isEmpty())
        assertTrue(events.any { it == AgentEvent.LogFrame("boot") })
        assertIs<AgentEvent.TurnCompleted>(events.last())
        Unit
    }

    @Test
    fun `reconnect accepts active and idle and streams logs from returned session version`() = runBlocking {
        for (status in listOf(HostedAgentSessionStatus.Active, HostedAgentSessionStatus.Idle)) {
            val id = Uuid.random().toString()
            val client = FakeHostedAgentClient()
            client.getResults += session(id, version = "bound-v7", status = status)
            val provider = provider(client)

            provider.activate(binding(id), hasLocalEntries = true)
            provider.runTurn(turn("resume"), noTools).toList()

            assertContains(client.calls, "get:agent-a:$id")
            assertContains(client.calls, "logs:agent-a:bound-v7:$id")
            assertEquals(listOf(id), client.invokedSessionIds)
        }
    }

    @Test
    fun `activation boundedly polls creating and updating until active`() = runBlocking {
        val id = Uuid.random().toString()
        val client = FakeHostedAgentClient()
        client.getResults += session(id, status = HostedAgentSessionStatus.Creating)
        client.getResults += session(id, status = HostedAgentSessionStatus.Updating)
        client.getResults += session(id, status = HostedAgentSessionStatus.Active)
        val provider = provider(client)

        provider.activate(binding(id), hasLocalEntries = true)

        assertEquals(3, client.calls.count { it == "get:agent-a:$id" })
    }

    @Test
    fun `terminal and unknown sessions fail explicitly without create`() = runBlocking {
        val terminal = listOf(
            HostedAgentSessionStatus.Failed,
            HostedAgentSessionStatus.Deleting,
            HostedAgentSessionStatus.Deleted,
            HostedAgentSessionStatus.Expired,
            HostedAgentSessionStatus.Unknown,
        )
        for (status in terminal) {
            val id = Uuid.random().toString()
            val client = FakeHostedAgentClient().also { it.getResults += session(id, status = status) }
            val failure = runCatching {
                provider(client).activate(binding(id), hasLocalEntries = true)
            }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertTrue(client.calls.none { it.startsWith("create:") })
        }
    }

    @Test
    fun `missing non-empty durable binding fails and never creates replacement`() = runBlocking {
        val id = Uuid.random().toString()
        val client = FakeHostedAgentClient().also {
            it.getResults += HostedSessionNotFoundException("missing")
        }

        val failure = runCatching { provider(client).activate(binding(id), hasLocalEntries = true) }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertContains(failure.message.orEmpty(), "cannot be replaced")
        assertTrue(client.calls.none { it.startsWith("create:") })
    }

    @Test
    fun `configured agent mismatch and returned id mismatch fail without adoption`() = runBlocking {
        val id = Uuid.random().toString()
        val wrongAgentClient = FakeHostedAgentClient()
        val wrongAgent = runCatching {
            provider(wrongAgentClient).activate(HostedSessionBinding("other", id), false)
        }.exceptionOrNull()
        assertIs<IllegalStateException>(wrongAgent)
        assertTrue(wrongAgentClient.calls.isEmpty())

        val wrongIdClient = FakeHostedAgentClient().also { it.getResults += session(Uuid.random().toString()) }
        val wrongId = runCatching { provider(wrongIdClient).activate(binding(id), true) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(wrongId)
        assertContains(wrongId.message.orEmpty(), "requested binding")
    }

    @Test
    fun `conflicting and ambiguous deterministic create reconcile only the reserved id`() = runBlocking {
        for (failure in listOf<RuntimeException>(
            HostedSessionCreateConflictException("409"),
            HostedSessionCreateAmbiguousException("timeout"),
        )) {
            val id = Uuid.random().toString()
            val client = FakeHostedAgentClient()
            client.getResults += HostedSessionNotFoundException("initial missing")
            client.deterministicFailure = failure
            client.getResults += HostedSessionNotFoundException("not visible yet")
            client.getResults += session(id)

            provider(client).activate(binding(id), hasLocalEntries = false)

            assertEquals(listOf(id), client.deterministicCreateIds)
            assertEquals(3, client.calls.count { it == "get:agent-a:$id" })
        }
    }

    @Test
    fun `ephemeral resources are delete-only on detach and close`() = runBlocking {
        val client = FakeHostedAgentClient()
        client.ephemeralCreates += session("ephemeral-1")
        client.ephemeralCreates += session("ephemeral-2")
        val provider = provider(client)

        provider.activate(binding = null, hasLocalEntries = false)
        provider.detach()
        provider.activate(binding = null, hasLocalEntries = false)
        provider.close()

        assertEquals(listOf("ephemeral-1", "ephemeral-2"), client.deletedSessionIds)
        assertTrue(client.calls.none { it.startsWith("stop:") })
    }

    @Test
    fun `turn cancellation retains durable binding`() = runBlocking {
        val id = Uuid.random().toString()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val client = FakeHostedAgentClient(started, gate)
        client.getResults += session(id)
        val provider = provider(client)
        provider.activate(binding(id), hasLocalEntries = true)
        val collector = launch { provider.runTurn(turn("cancel"), noTools).collect {} }
        try {
            withTimeout(2_000) { started.await() }

            withTimeout(2_000) { collector.cancelAndJoin() }
            provider.runTurn(turn("next"), noTools).toList()
        } finally {
            withContext(NonCancellable) {
                try {
                    collector.cancelAndJoin()
                } finally {
                    provider.close()
                }
            }
        }

        assertTrue(collector.isCancelled)
        assertEquals(listOf(id, id), client.invokedSessionIds)
        assertTrue(client.deletedSessionIds.isEmpty())
        assertEquals(1, client.calls.count { it == "get:agent-a:$id" })
    }

    private fun provider(client: FakeHostedAgentClient) =
        HostedProvider(client, "agent-a", "repo/image:tag", sessionPollAttempts = 4, sessionPollIntervalMs = 0)

    private fun binding(id: String) = HostedSessionBinding("agent-a", id)

    private fun session(
        id: String,
        version: String = "v1",
        status: HostedAgentSessionStatus = HostedAgentSessionStatus.Active,
    ) = HostedAgentSession(id, version, status)

    private fun turn(text: String) = TurnRequest(context, listOf(userEntry(text)))

    private fun userEntry(text: String) =
        UserEntry(Uuid.random(), null, Clock.System.now(), text)
}

private class FakeHostedAgentClient(
    private val invokeStarted: CompletableDeferred<Unit>? = null,
    private val firstInvokeGate: CompletableDeferred<Unit>? = null,
) : HostedAgentClient {
    val calls = mutableListOf<String>()
    val getResults = ArrayDeque<Any>()
    val ephemeralCreates = ArrayDeque<HostedAgentSession>()
    var deterministicCreate: HostedAgentSession? = null
    var deterministicFailure: RuntimeException? = null
    val deterministicCreateIds = mutableListOf<String>()
    val invokedSessionIds = mutableListOf<String>()
    val deletedSessionIds = mutableListOf<String>()
    private var invocationCount = 0

    override suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion {
        calls += "select:$agentName:$containerImage"
        return HostedAgentVersion("v1")
    }

    override suspend fun configureResponsesEndpoint(agentName: String, version: String) {
        calls += "configure:$agentName:$version"
    }

    override suspend fun createSession(agentName: String, version: String): HostedAgentSession {
        calls += "createEphemeral:$agentName:$version"
        return ephemeralCreates.removeFirstOrNull() ?: HostedAgentSession("ephemeral", version)
    }

    override suspend fun createSession(agentName: String, version: String, sessionId: String): HostedAgentSession {
        calls += "create:$agentName:$version:$sessionId"
        deterministicCreateIds += sessionId
        deterministicFailure?.let { throw it }
        return deterministicCreate ?: HostedAgentSession(sessionId, version)
    }

    override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession {
        calls += "get:$agentName:$sessionId"
        return when (val result = getResults.removeFirstOrNull() ?: HostedSessionNotFoundException("missing")) {
            is HostedAgentSession -> result
            is RuntimeException -> throw result
            else -> error("unexpected fake result $result")
        }
    }

    override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse {
        calls += "invoke:$agentName:$sessionId:$input"
        invokedSessionIds += sessionId
        invocationCount++
        if (invocationCount == 1 && invokeStarted != null && firstInvokeGate != null) {
            invokeStarted.complete(Unit)
            firstInvokeGate.await()
        }
        delay(1)
        return HostedAgentResponse("hosted answer", Usage(7, 3, 10))
    }

    override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> = flow {
        calls += "logs:$agentName:$version:$sessionId"
        emit("boot")
    }

    override suspend fun deleteSession(agentName: String, sessionId: String) {
        calls += "delete:$agentName:$sessionId"
        deletedSessionIds += sessionId
    }

    override suspend fun close() {
        calls += "close"
    }
}
