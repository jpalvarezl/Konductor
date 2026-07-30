package com.konductor.acp

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.provider.hosted.HostedAgentClient
import com.konductor.provider.hosted.HostedAgentResponse
import com.konductor.provider.hosted.HostedAgentSession
import com.konductor.provider.hosted.HostedAgentVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class HostedSessionLifecycleCleanupTest {
    private val context = AgentContext("server-owned", emptyList(), "hosted")

    @Test
    fun `cleanup is non-cancellable continues and suppresses every failure on the primary`() = runBlocking {
        val primary = IllegalStateException("test failed")
        val closeFailure = IOException("close failed")
        val cancellation = CancellationException("delegate cancelled")
        val attempted = mutableListOf<String>()

        val cleanup = launch {
            cancel()
            cleanupKnownResources(
                primary,
                listOf(
                    {
                        currentCoroutineContext().ensureActive()
                        attempted += "first"
                        throw closeFailure
                    },
                    {
                        attempted += "second"
                        throw cancellation
                    },
                    { attempted += "third" },
                ),
            )
        }
        cleanup.join()

        assertTrue(cleanup.isCancelled)
        assertEquals(listOf("first", "second", "third"), attempted)
        assertEquals(listOf(closeFailure, cancellation), primary.suppressedExceptions)
    }

    @Test
    fun `factory close continues after cancellation and retries only failed providers`() = runBlocking {
        val firstDelegate = CloseRecordingHostedClient(failuresBeforeSuccess = 1, cancelOnFailure = true)
        val secondDelegate = CloseRecordingHostedClient()
        val firstClient = RecordingLiveHostedClient(firstDelegate)
        val secondClient = RecordingLiveHostedClient(secondDelegate)
        val clients = ArrayDeque(listOf(firstClient, secondClient))
        val factory = factory { clients.removeFirst() }
        factory.create(session())
        factory.create(session())

        val cancellation = assertFailsWith<CancellationException> { factory.close() }

        assertEquals("close cancelled", cancellation.message)
        assertEquals(1, firstDelegate.closeAttempts)
        assertEquals(1, secondDelegate.closeAttempts)
        assertFalse(firstClient.isClosed)
        assertTrue(secondClient.isClosed)

        factory.close()

        assertEquals(2, firstDelegate.closeAttempts)
        assertEquals(1, secondDelegate.closeAttempts)
        assertTrue(firstClient.isClosed)
    }

    @Test
    fun `factory release retains a provider whose close fails so release can retry`() = runBlocking {
        val delegate = CloseRecordingHostedClient(failuresBeforeSuccess = 1)
        val client = RecordingLiveHostedClient(delegate)
        val factory = factory { client }
        val session = session()
        factory.create(session)

        assertFailsWith<IOException> { factory.release(session.id) }
        assertFalse(client.isClosed)

        factory.release(session.id)

        assertEquals(2, delegate.closeAttempts)
        assertTrue(client.isClosed)
    }

    @Test
    fun `factory close runs providers when its caller is already cancelled`() = runBlocking {
        val delegate = CloseRecordingHostedClient()
        val client = RecordingLiveHostedClient(delegate)
        val factory = factory { client }
        factory.create(session())

        val cleanup = launch {
            cancel()
            factory.close()
        }
        cleanup.join()

        assertTrue(cleanup.isCancelled)
        assertEquals(1, delegate.closeAttempts)
        assertTrue(client.isClosed)
    }

    private fun factory(clientFactory: () -> RecordingLiveHostedClient) =
        LiveAcpRuntimeFactory(context, "agent-a", "repo/image:tag", clientFactory)

    private fun session() = Session(
        id = Uuid.random(),
        name = null,
        cwd = Path.of("").toAbsolutePath(),
        modelName = "hosted",
        createdAt = Clock.System.now(),
    )
}

private class CloseRecordingHostedClient(
    private var failuresBeforeSuccess: Int = 0,
    private val cancelOnFailure: Boolean = false,
) : HostedAgentClient {
    var closeAttempts = 0
        private set

    override suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion =
        error("not used")

    override suspend fun configureResponsesEndpoint(agentName: String, version: String) = error("not used")

    override suspend fun createSession(agentName: String, version: String): HostedAgentSession = error("not used")

    override suspend fun createSession(
        agentName: String,
        version: String,
        sessionId: String,
    ): HostedAgentSession = error("not used")

    override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession = error("not used")

    override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse =
        error("not used")

    override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> = emptyFlow()

    override suspend fun deleteSession(agentName: String, sessionId: String) = error("not used")

    override suspend fun close() {
        closeAttempts++
        currentCoroutineContext().ensureActive()
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            if (cancelOnFailure) throw CancellationException("close cancelled")
            throw IOException("close failed")
        }
    }
}
