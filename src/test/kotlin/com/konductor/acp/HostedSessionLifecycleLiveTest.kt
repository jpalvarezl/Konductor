package com.konductor.acp

import com.konductor.session.persistedCandidate
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionId
import com.azure.ai.agents.AgentsClient
import com.azure.ai.agents.AgentsClientBuilder
import com.azure.core.exception.ResourceNotFoundException
import com.azure.core.test.annotation.LiveOnly
import com.azure.identity.DefaultAzureCredentialBuilder
import com.konductor.agent.AgentLoop
import com.konductor.agent.NoToolExecutor
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
import com.konductor.session.NoOpSessionStore
import com.openai.client.OpenAIClient
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** Focused LIVE-only proof of I028 against the indexed foundry-sdk-deployment/java Hosted resource. */
@LiveOnly
class HostedSessionLifecycleLiveTest {
    @Test
    fun `durable new resume cancellation and ephemeral cleanup`(@TempDir root: Path) = runBlocking {
        val endpoint = requiredEnvironment("FOUNDRY_PROJECT_ENDPOINT")
        val image = requiredEnvironment("FOUNDRY_AGENT_CONTAINER_IMAGE")
        val agentName = requiredEnvironment("KONDUCTOR_HOSTED_AGENT_NAME")
        val credential = DefaultAzureCredentialBuilder().build()
        val inspectionClient = AgentsClientBuilder()
            .endpoint(endpoint)
            .credential(credential)
            .allowPreview(true)
            .buildAgentsClient()
        val durableClient = recordingClient(endpoint, agentName, credential)
        val durableProvider = HostedProvider(durableClient, agentName, image)
        val store = JsonlSessionStore(root.resolve("sessions"))
        val first = store.persistedCandidate(root.resolve("workspace"), "hosted", null)
        val loop = AgentLoop(
            ProviderRuntime(durableProvider),
            NoToolExecutor,
            AgentContext("server-owned", emptyList(), "hosted"),
            store,
            first,
        )
        val durableIds = mutableListOf<String>()

        try {
            loop.activateInitialSession(resuming = false)
            assertSuccessful(loop.runTurn("live durable A first").toList())
            val firstId = first.id.toString().also(durableIds::add)
            assertEquals(listOf(firstId), durableClient.deterministicCreates)

            val second = loop.newSession()
            assertSuccessful(loop.runTurn("live durable B isolated").toList())
            val secondId = second.id.toString().also(durableIds::add)
            assertEquals(listOf(firstId, secondId), durableClient.deterministicCreates)
            assertTrue(durableClient.deletes.isEmpty())

            loop.resume(first.id)
            assertTrue(durableClient.gets.contains(firstId))
            assertSuccessful(loop.runTurn("live durable A resumed").toList())

            val cancelled = launch { loop.runTurn("live cancellation").toList() }
            delay(100)
            cancelled.cancelAndJoin()
            assertEquals(firstId, inspectionClient.getSession(agentName, firstId).agentSessionId)
            assertSuccessful(loop.runTurn("live after cancellation").toList())

            durableProvider.close()
            assertTrue(durableClient.deletes.isEmpty(), "durable close must detach without delete")
            assertEquals(firstId, inspectionClient.getSession(agentName, firstId).agentSessionId)
            assertEquals(secondId, inspectionClient.getSession(agentName, secondId).agentSessionId)

            val ephemeralClient = recordingClient(endpoint, agentName, credential)
            val ephemeralProvider = HostedProvider(ephemeralClient, agentName, image)
            val ephemeralLoop = AgentLoop(
                ProviderRuntime(ephemeralProvider),
                NoToolExecutor,
                AgentContext("server-owned", emptyList(), "hosted"),
                NoOpSessionStore,
            )
            ephemeralLoop.activateInitialSession(resuming = false)
            assertSuccessful(ephemeralLoop.runTurn("live ephemeral").toList())
            val ephemeralId = ephemeralClient.ephemeralCreates.single()
            ephemeralProvider.close()
            assertEquals(listOf(ephemeralId), ephemeralClient.deletes)
            assertEventuallyMissing(inspectionClient, agentName, ephemeralId)
        } finally {
            runCatching { durableProvider.close() }
            durableIds.forEach { id -> runCatching { inspectionClient.deleteSession(agentName, id) } }
        }
    }

    @Test
    fun `ACP new isolates and later load reconnects durable bindings`(@TempDir root: Path) = runBlocking {
        val endpoint = requiredEnvironment("FOUNDRY_PROJECT_ENDPOINT")
        val image = requiredEnvironment("FOUNDRY_AGENT_CONTAINER_IMAGE")
        val agentName = requiredEnvironment("KONDUCTOR_HOSTED_AGENT_NAME")
        val credential = DefaultAzureCredentialBuilder().build()
        val inspectionClient = AgentsClientBuilder()
            .endpoint(endpoint)
            .credential(credential)
            .allowPreview(true)
            .buildAgentsClient()
        val store = JsonlSessionStore(root.resolve("acp-sessions"))
        val workspace = root.resolve("acp-workspace").also { java.nio.file.Files.createDirectory(it) }
        val params = SessionCreationParameters(workspace.toString(), emptyList(), emptyList(), null)
        val context = AgentContext("server-owned", emptyList(), "hosted")
        val firstFactory = LiveAcpRuntimeFactory(context, agentName, image) {
            recordingClient(endpoint, agentName, credential)
        }
        val durableIds = mutableListOf<String>()

        try {
            val support = KonductorAgentSupport(firstFactory, store, com.konductor.compaction.CompactionSettings(false))
            val first = support.createSession(params)
            first.prompt(listOf(ContentBlock.Text("live ACP A")), null).toList()
            val firstId = first.sessionId.value.also(durableIds::add)
            val second = support.createSession(params)
            second.prompt(listOf(ContentBlock.Text("live ACP B")), null).toList()
            val secondId = second.sessionId.value.also(durableIds::add)

            assertEquals(
                listOf(firstId),
                requireNotNull(firstFactory.clients[Uuid.parse(firstId)]).deterministicCreates,
            )
            assertEquals(
                listOf(secondId),
                requireNotNull(firstFactory.clients[Uuid.parse(secondId)]).deterministicCreates,
            )
            firstFactory.close()
            assertTrue(firstFactory.clients.values.all { it.deletes.isEmpty() })
            assertEquals(firstId, inspectionClient.getSession(agentName, firstId).agentSessionId)
            assertEquals(secondId, inspectionClient.getSession(agentName, secondId).agentSessionId)

            val loadFactory = LiveAcpRuntimeFactory(context, agentName, image) {
                recordingClient(endpoint, agentName, credential)
            }
            val loadSupport = KonductorAgentSupport(
                loadFactory,
                store,
                com.konductor.compaction.CompactionSettings(false),
            )
            val loaded = loadSupport.loadSession(SessionId(firstId), params)
            assertTrue(loadFactory.clients[Uuid.parse(firstId)]?.gets?.contains(firstId) == true)
            loaded.prompt(listOf(ContentBlock.Text("live ACP A loaded")), null).toList()
            loadFactory.close()
            assertTrue(loadFactory.clients.values.all { it.deletes.isEmpty() })
        } finally {
            runCatching { firstFactory.close() }
            durableIds.forEach { id -> runCatching { inspectionClient.deleteSession(agentName, id) } }
        }
    }

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

    private fun assertSuccessful(events: List<AgentEvent>) {
        assertFalse(events.any { it is AgentEvent.Failed }, events.filterIsInstance<AgentEvent.Failed>().toString())
        assertTrue(events.any { it is AgentEvent.TurnCompleted })
    }

    private suspend fun assertEventuallyMissing(client: AgentsClient, agentName: String, sessionId: String) {
        repeat(10) { attempt ->
            try {
                client.getSession(agentName, sessionId)
            } catch (_: ResourceNotFoundException) {
                return
            }
            if (attempt < 9) delay(1_000)
        }
        error("Ephemeral Hosted session '$sessionId' still exists after delete-only cleanup.")
    }

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.trim()?.ifBlank { null }) { "$name is required for the Hosted live test." }
}

private class LiveAcpRuntimeFactory(
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
        providers.remove(sessionId)?.close()
    }

    override suspend fun close() {
        providers.values.toList().also { providers.clear() }.forEach { it.close() }
    }
}

private class RecordingLiveHostedClient(private val delegate: HostedAgentClient) : HostedAgentClient {
    val deterministicCreates = mutableListOf<String>()
    val ephemeralCreates = mutableListOf<String>()
    val gets = mutableListOf<String>()
    val deletes = mutableListOf<String>()

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
        deletes += sessionId
        delegate.deleteSession(agentName, sessionId)
    }

    override suspend fun close() = delegate.close()
}
