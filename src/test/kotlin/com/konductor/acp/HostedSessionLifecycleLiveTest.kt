package com.konductor.acp

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.azure.ai.agents.AgentsClient
import com.azure.ai.agents.AgentsClientBuilder
import com.azure.core.test.annotation.LiveOnly
import com.azure.identity.DefaultAzureCredentialBuilder
import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
import com.konductor.compaction.CompactionSettings
import com.konductor.core.models.AgentContext
import com.konductor.provider.AgentEvent
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.hosted.AzureHostedAgentClient
import com.konductor.provider.hosted.HostedAgentClient
import com.konductor.provider.hosted.HostedAgentResponse
import com.konductor.provider.hosted.HostedAgentSession
import com.konductor.provider.hosted.HostedAgentVersion
import com.konductor.provider.hosted.HostedProvider
import com.konductor.session.JsonlSessionStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Focused LIVE-only state-isolation and reconnect proof for issue #102. */
@LiveOnly
class HostedSessionLifecycleLiveTest {
    @Test
    fun `AgentLoop isolates random markers and reconnects A through a fresh provider`(
        @TempDir root: Path,
    ) = runBlocking {
        val endpoint = requiredEnvironment("FOUNDRY_PROJECT_ENDPOINT")
        val image = requiredEnvironment("FOUNDRY_AGENT_CONTAINER_IMAGE")
        val agentName = requiredEnvironment("KONDUCTOR_HOSTED_AGENT_NAME")
        val credential = DefaultAzureCredentialBuilder().build()
        val inspectionClient = inspectionClient(endpoint, credential)
        val store = JsonlSessionStore(root.resolve("sessions"))
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val context = AgentContext("server-owned", emptyList(), "hosted")
        val knownRemoteIds = linkedSetOf<String>()
        val markerA = "agent-loop-a-${Uuid.random()}"
        val markerB = "agent-loop-b-${Uuid.random()}"
        var originalProvider: HostedProvider? = null
        var reconnectProvider: HostedProvider? = null
        var primaryFailure: Throwable? = null

        try {
            val first = store.create(workspace, "hosted", null)
            val firstId = first.id.toString().also(knownRemoteIds::add)
            val originalClient = recordingClient(endpoint, agentName, credential)
            originalProvider = HostedProvider(originalClient, agentName, image)
            val originalLoop = AgentLoop(
                ProviderRuntime(originalProvider),
                NoToolExecutor,
                context,
                store,
                first,
            )

            originalLoop.activateInitialSession(resuming = false)
            assertEquals("remembered $markerA", runAgentLoopTurn(originalLoop, "remember $markerA"))

            val second = originalLoop.newSession()
            val secondId = second.id.toString().also(knownRemoteIds::add)
            val emptyB = runAgentLoopTurn(originalLoop, "recall")
            assertEquals(NONE, emptyB)
            assertFalse(markerA in emptyB)
            assertEquals("remembered $markerB", runAgentLoopTurn(originalLoop, "remember $markerB"))
            assertEquals(markerB, runAgentLoopTurn(originalLoop, "recall"))
            assertEquals(listOf(firstId, secondId), originalClient.deterministicCreates)
            assertTrue(originalClient.ephemeralCreates.isEmpty())
            assertTrue(originalClient.deletes.isEmpty(), "durable switches must not delete either binding")

            // Provider/client reconstruction is intentional: no in-process provider state can satisfy the recall.
            withContext(NonCancellable) { originalProvider.close() }
            assertTrue(originalClient.isClosed)
            assertEquals(firstId, inspectionClient.getSession(agentName, firstId).agentSessionId)
            assertEquals(secondId, inspectionClient.getSession(agentName, secondId).agentSessionId)

            val reconnectClient = recordingClient(endpoint, agentName, credential)
            reconnectProvider = HostedProvider(reconnectClient, agentName, image)
            val loadedA = store.load(first.id)
            val reconnectLoop = AgentLoop(
                ProviderRuntime(reconnectProvider),
                NoToolExecutor,
                context,
                store,
                loadedA,
            )
            reconnectLoop.activateInitialSession(resuming = true)

            assertEquals(listOf(firstId), reconnectClient.gets)
            assertTrue(reconnectClient.deterministicCreates.isEmpty())
            assertTrue(reconnectClient.ephemeralCreates.isEmpty())
            val recalledA = runAgentLoopTurn(reconnectLoop, "recall")
            assertEquals(markerA, recalledA)
            assertFalse(markerB in recalledA)
            assertTrue(reconnectClient.deletes.isEmpty(), "durable reconnect must not delete either binding")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupKnownResources(
                primaryFailure,
                buildList {
                    add { reconnectProvider?.close() }
                    add { originalProvider?.close() }
                    knownRemoteIds.forEach { id ->
                        add { inspectionClient.deleteSession(agentName, id) }
                    }
                },
            )
        }
    }

    @Test
    fun `ACP isolates random markers and loads A through a fresh factory`(@TempDir root: Path) = runBlocking {
        val endpoint = requiredEnvironment("FOUNDRY_PROJECT_ENDPOINT")
        val image = requiredEnvironment("FOUNDRY_AGENT_CONTAINER_IMAGE")
        val agentName = requiredEnvironment("KONDUCTOR_HOSTED_AGENT_NAME")
        val credential = DefaultAzureCredentialBuilder().build()
        val inspectionClient = inspectionClient(endpoint, credential)
        val store = JsonlSessionStore(root.resolve("acp-sessions"))
        val workspace = Files.createDirectory(root.resolve("acp-workspace"))
        val params = SessionCreationParameters(workspace.toString(), emptyList(), emptyList(), null)
        val context = AgentContext("server-owned", emptyList(), "hosted")
        val knownRemoteIds = linkedSetOf<String>()
        val markerA = "acp-a-${Uuid.random()}"
        val markerB = "acp-b-${Uuid.random()}"
        val originalFactory = LiveAcpRuntimeFactory(context, agentName, image) {
            recordingClient(endpoint, agentName, credential)
        }
        var loadFactory: LiveAcpRuntimeFactory? = null
        var primaryFailure: Throwable? = null

        try {
            val originalSupport = KonductorAgentSupport(originalFactory, store, CompactionSettings(false))
            val first = originalSupport.createSession(params)
            val firstId = first.sessionId.value.also(knownRemoteIds::add)
            assertEquals("remembered $markerA", promptAssistantText(first, "remember $markerA"))

            val second = originalSupport.createSession(params)
            val secondId = second.sessionId.value.also(knownRemoteIds::add)
            val emptyB = promptAssistantText(second, "recall")
            assertEquals(NONE, emptyB)
            assertFalse(markerA in emptyB)
            assertEquals("remembered $markerB", promptAssistantText(second, "remember $markerB"))
            assertEquals(markerB, promptAssistantText(second, "recall"))
            assertEquals(
                listOf(firstId),
                requireNotNull(originalFactory.clients[Uuid.parse(firstId)]).deterministicCreates,
            )
            assertEquals(
                listOf(secondId),
                requireNotNull(originalFactory.clients[Uuid.parse(secondId)]).deterministicCreates,
            )
            assertTrue(originalFactory.clients.values.all { it.ephemeralCreates.isEmpty() })

            // Closing the connection-owned factory must detach durable sessions and close every scoped client.
            withContext(NonCancellable) { originalFactory.close() }
            assertTrue(originalFactory.clients.values.all { it.isClosed })
            assertTrue(originalFactory.clients.values.all { it.deletes.isEmpty() })
            assertEquals(firstId, inspectionClient.getSession(agentName, firstId).agentSessionId)
            assertEquals(secondId, inspectionClient.getSession(agentName, secondId).agentSessionId)

            loadFactory = LiveAcpRuntimeFactory(context, agentName, image) {
                recordingClient(endpoint, agentName, credential)
            }
            val loadSupport = KonductorAgentSupport(loadFactory, store, CompactionSettings(false))
            val loadedA = loadSupport.loadSession(SessionId(firstId), params)
            val reconnectClient = requireNotNull(loadFactory.clients[Uuid.parse(firstId)])
            assertEquals(listOf(firstId), reconnectClient.gets)
            assertTrue(reconnectClient.deterministicCreates.isEmpty())
            assertTrue(reconnectClient.ephemeralCreates.isEmpty())

            val recalledA = promptAssistantText(loadedA, "recall")
            assertEquals(markerA, recalledA)
            assertFalse(markerB in recalledA)
            assertTrue(reconnectClient.deletes.isEmpty())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupKnownResources(
                primaryFailure,
                buildList {
                    add { loadFactory?.close() }
                    add { originalFactory.close() }
                    knownRemoteIds.forEach { id ->
                        add { inspectionClient.deleteSession(agentName, id) }
                    }
                },
            )
        }
    }

    private fun inspectionClient(
        endpoint: String,
        credential: com.azure.core.credential.TokenCredential,
    ): AgentsClient = AgentsClientBuilder()
        .endpoint(endpoint)
        .credential(credential)
        .allowPreview(true)
        .buildAgentsClient()

    private fun recordingClient(
        endpoint: String,
        agentName: String,
        credential: com.azure.core.credential.TokenCredential,
    ): RecordingLiveHostedClient {
        val builder = AgentsClientBuilder()
            .endpoint(endpoint)
            .credential(credential)
            .allowPreview(true)
        val agentsClient = builder.buildAgentsClient()
        val openAIClient = builder.buildAgentScopedOpenAIClient(agentName)
        return RecordingLiveHostedClient(AzureHostedAgentClient(agentsClient, openAIClient))
    }

    private suspend fun runAgentLoopTurn(loop: AgentLoop, input: String): String {
        val events = loop.runTurn(input).toList()
        assertSuccessful(events)
        return events.filterIsInstance<AgentEvent.TextDelta>().joinToString(separator = "") { it.text }
    }

    private suspend fun promptAssistantText(session: AgentSession, input: String): String {
        val events = session.prompt(listOf(ContentBlock.Text(input)), null).toList()
        val response = events.last() as Event.PromptResponseEvent
        assertEquals(StopReason.END_TURN, response.response.stopReason)
        return events.mapNotNull { (it as? Event.SessionUpdateEvent)?.update as? SessionUpdate.AgentMessageChunk }
            .map { (it.content as ContentBlock.Text).text }
            .filterNot { it.startsWith(ACP_LOG_PREFIX) }
            .joinToString(separator = "")
    }

    private fun assertSuccessful(events: List<AgentEvent>) {
        assertFalse(events.any { it is AgentEvent.Failed }, events.filterIsInstance<AgentEvent.Failed>().toString())
        assertTrue(events.any { it is AgentEvent.TurnCompleted })
    }

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.trim()?.ifBlank { null }) { "$name is required for the Hosted live test." }

    private companion object {
        const val NONE = "none"
        const val ACP_LOG_PREFIX = "📋 "
    }
}

internal suspend fun cleanupKnownResources(
    primaryFailure: Throwable?,
    actions: Iterable<suspend () -> Unit>,
) {
    val cleanupFailures = withContext(NonCancellable) {
        buildList {
            for (action in actions) {
                try {
                    action()
                } catch (failure: Throwable) {
                    add(failure)
                }
            }
        }
    }
    if (cleanupFailures.isEmpty()) return

    if (primaryFailure != null) {
        cleanupFailures.forEach { failure ->
            if (failure !== primaryFailure) primaryFailure.addSuppressed(failure)
        }
        return
    }

    val first = cleanupFailures.first()
    cleanupFailures.drop(1).forEach { failure ->
        if (failure !== first) first.addSuppressed(failure)
    }
    throw first
}

/** Sequential, live-test-only ACP composition with retryable teardown bookkeeping. */
internal class LiveAcpRuntimeFactory(
    private val context: AgentContext,
    private val agentName: String,
    private val image: String,
    private val clientFactory: () -> RecordingLiveHostedClient,
) : AcpSessionRuntimeFactory {
    override val defaultModelName: String = "hosted"
    val clients = mutableMapOf<Uuid, RecordingLiveHostedClient>()
    private val providers = mutableMapOf<Uuid, HostedProvider>()

    override fun create(session: com.konductor.core.models.Session): AcpSessionRuntime {
        check(session.id !in providers) { "ACP session '${session.id}' is already active in this connection." }
        val client = clientFactory()
        val provider = HostedProvider(client, agentName, image)
        clients[session.id] = client
        providers[session.id] = provider
        return AcpSessionRuntime(ProviderRuntime(provider), context, NoToolExecutor)
    }

    override suspend fun release(sessionId: Uuid) {
        withContext(NonCancellable) {
            val provider = providers[sessionId] ?: return@withContext
            provider.close()
            providers.remove(sessionId, provider)
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            val ownedProviders = providers.toList()
            var failure: Throwable? = null
            for ((sessionId, provider) in ownedProviders) {
                try {
                    provider.close()
                    providers.remove(sessionId, provider)
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            failure?.let { throw it }
        }
    }
}

internal class RecordingLiveHostedClient(private val delegate: HostedAgentClient) : HostedAgentClient {
    val deterministicCreates = mutableListOf<String>()
    val ephemeralCreates = mutableListOf<String>()
    val gets = mutableListOf<String>()
    val deletes = mutableListOf<String>()
    var isClosed: Boolean = false
        private set

    override suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion =
        delegate.selectOrCreateAgentVersion(agentName, containerImage)

    override suspend fun configureResponsesEndpoint(agentName: String, version: String) =
        delegate.configureResponsesEndpoint(agentName, version)

    override suspend fun createSession(agentName: String, version: String): HostedAgentSession =
        delegate.createSession(agentName, version).also { ephemeralCreates += it.sessionId }

    override suspend fun createSession(
        agentName: String,
        version: String,
        sessionId: String,
    ): HostedAgentSession = delegate.createSession(agentName, version, sessionId).also {
        deterministicCreates += sessionId
    }

    override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession =
        delegate.getSession(agentName, sessionId).also { gets += sessionId }

    override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse =
        delegate.invoke(agentName, sessionId, input)

    override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> =
        delegate.streamSessionLogs(agentName, version, sessionId)

    override suspend fun deleteSession(agentName: String, sessionId: String) {
        withContext(NonCancellable) {
            deletes += sessionId
            delegate.deleteSession(agentName, sessionId)
        }
    }

    override suspend fun close() = withContext(NonCancellable) {
        if (isClosed) return@withContext
        delegate.close()
        isClosed = true
    }
}
