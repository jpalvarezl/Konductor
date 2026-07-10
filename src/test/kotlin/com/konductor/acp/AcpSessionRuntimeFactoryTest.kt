package com.konductor.acp

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCall
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.TurnRequest
import com.konductor.provider.hosted.HostedAgentClient
import com.konductor.provider.hosted.HostedAgentResponse
import com.konductor.provider.hosted.HostedAgentSession
import com.konductor.provider.hosted.HostedAgentVersion
import com.konductor.provider.hosted.HostedProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class AcpSessionRuntimeFactoryTest {
    private val fakeCredential = TokenCredential { _ ->
        Mono.just(AccessToken("fake-token", OffsetDateTime.now().plusHours(1)))
    }

    @Test
    fun `each session gets cwd-bound context tools and provider`(@TempDir root: Path) {
        val workspaceA = Files.createDirectory(root.resolve("workspace-a"))
        val workspaceB = Files.createDirectory(root.resolve("workspace-b"))
        Files.writeString(workspaceA.resolve("marker.txt"), "from-a")
        Files.writeString(workspaceB.resolve("marker.txt"), "from-b")
        val providers = mutableListOf<RecordingProvider>()
        val factory = ConfigurationAcpSessionRuntimeFactory(promptConfiguration(), toolAllow = null) {
            RecordingProvider().also(providers::add)
        }

        val sessionA = session(workspaceA)
        val sessionB = session(workspaceB)
        val runtimeA = factory.create(sessionA)
        val runtimeB = factory.create(sessionB)
        val resultA = runBlocking {
            runtimeA.toolExecutor.execute(ToolCall("a", "read", """{"path":"marker.txt"}"""))
        }
        val resultB = runBlocking {
            runtimeB.toolExecutor.execute(ToolCall("b", "read", """{"path":"marker.txt"}"""))
        }

        assertContains(runtimeA.context.dynamicPreamble, workspaceA.toAbsolutePath().normalize().toString())
        assertContains(runtimeB.context.dynamicPreamble, workspaceB.toAbsolutePath().normalize().toString())
        assertContains(resultA.output, "from-a")
        assertContains(resultB.output, "from-b")
        assertNotSame(runtimeA.provider, runtimeB.provider)
        val duplicate = assertFailsWith<IllegalStateException> { factory.create(sessionA) }
        assertContains(duplicate.message.orEmpty(), "already active")

        runBlocking { factory.close() }
        assertTrue(providers.all { it.closed })
    }

    @Test
    fun `hosted ACP sessions own distinct server sessions`() {
        val clients = mutableListOf<RecordingHostedClient>()
        var nextSession = 0
        val factory = ConfigurationAcpSessionRuntimeFactory(hostedConfiguration(), toolAllow = null) {
            val client = RecordingHostedClient("hosted-${++nextSession}")
            clients += client
            HostedProvider(client, agentName = "hosted-agent", containerImage = "repo/image:tag")
        }
        val cwd = Path.of("").toAbsolutePath()
        val runtimeA = factory.create(session(cwd))
        val runtimeB = factory.create(session(cwd))

        runBlocking {
            runtimeA.provider.runTurn(turn(runtimeA.context, "one"), runtimeA.toolExecutor).toList()
            runtimeB.provider.runTurn(turn(runtimeB.context, "two"), runtimeB.toolExecutor).toList()
            factory.close()
        }

        assertEquals(listOf("hosted-1"), clients[0].invokedSessionIds)
        assertEquals(listOf("hosted-2"), clients[1].invokedSessionIds)
        assertEquals(listOf("hosted-1"), clients[0].deletedSessionIds)
        assertEquals(listOf("hosted-2"), clients[1].deletedSessionIds)
        assertTrue(clients.all { it.closed })
    }

    private fun promptConfiguration(): Configuration =
        Configuration(
            projectEndpoint = "https://example.ai.azure.com/api/projects/project",
            tokenCredential = fakeCredential,
            model = "gpt-test",
            agentKind = AgentKind.Prompt,
        )

    private fun hostedConfiguration(): Configuration =
        promptConfiguration().copy(
            agentKind = AgentKind.Hosted,
            hostedAgentName = "hosted-agent",
            hostedAgentContainerImage = "repo/image:tag",
        )

    private fun session(cwd: Path): Session =
        Session(
            id = Uuid.random(),
            name = null,
            cwd = cwd.toAbsolutePath().normalize(),
            modelName = "gpt-test",
            createdAt = Clock.System.now(),
        )

    private fun turn(context: AgentContext, text: String): TurnRequest =
        TurnRequest(
            context,
            listOf(UserEntry(Uuid.random(), null, Clock.System.now(), text)),
        )
}

private class RecordingProvider : AgentProvider {
    override val kind: AgentKind = AgentKind.Prompt
    var closed: Boolean = false

    override fun runTurn(request: TurnRequest, tools: com.konductor.provider.ToolExecutor): Flow<AgentEvent> =
        emptyFlow()

    override suspend fun close() {
        closed = true
    }
}

private class RecordingHostedClient(
    private val sessionId: String,
) : HostedAgentClient {
    val invokedSessionIds = mutableListOf<String>()
    val deletedSessionIds = mutableListOf<String>()
    var closed: Boolean = false

    override suspend fun selectOrCreateAgentVersion(
        agentName: String,
        containerImage: String,
    ): HostedAgentVersion = HostedAgentVersion("v1")

    override suspend fun configureResponsesEndpoint(agentName: String, version: String) = Unit

    override suspend fun createSession(agentName: String, version: String): HostedAgentSession =
        HostedAgentSession(sessionId)

    override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession =
        HostedAgentSession(sessionId)

    override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse {
        invokedSessionIds += sessionId
        return HostedAgentResponse("ok")
    }

    override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> = emptyFlow()

    override suspend fun stopSession(agentName: String, sessionId: String) = Unit

    override suspend fun deleteSession(agentName: String, sessionId: String) {
        deletedSessionIds += sessionId
    }

    override suspend fun close() {
        closed = true
    }
}
