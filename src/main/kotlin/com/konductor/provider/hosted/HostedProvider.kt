package com.konductor.provider.hosted

import com.konductor.config.Configuration
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Hosted-kind [AgentProvider]. The server container owns history, compaction, and tool orchestration; Konductor
 * selects a hosted agent version, keeps one server-side session warm, invokes through the agent-scoped Responses
 * endpoint, and relays text/log frames back through the common provider seam.
 */
class HostedProvider(
    private val client: HostedAgentClient,
    private val agentName: String,
    private val containerImage: String,
) : AgentProvider {
    constructor(configuration: Configuration) : this(
        client = AzureHostedAgentClient(configuration),
        agentName = configuration.agentName
            ?: throw IllegalArgumentException("Hosted provider requires ${Configuration.ENV_AGENT_NAME}."),
        containerImage = configuration.hostedAgentContainerImage
            ?: throw IllegalArgumentException("Hosted provider requires ${Configuration.ENV_AGENT_CONTAINER_IMAGE}."),
    )

    override val kind: AgentKind = AgentKind.Hosted

    private val stateMutex = Mutex()
    private var selectedVersion: HostedAgentVersion? = null
    private var currentSession: HostedAgentSession? = null

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = channelFlow {
        val version = ensureVersion()
        val session = ensureSession(version, request.sessionRef)
        val logJob = launch {
            client.streamSessionLogs(agentName, version.version, session.sessionId).collect { line ->
                send(AgentEvent.LogFrame(line))
            }
        }

        try {
            val userText = request.history.filterIsInstance<UserEntry>().lastOrNull()?.text
                ?: error("HostedProvider requires a user entry in the turn history.")
            val response = client.invoke(agentName, session.sessionId, userText)
            if (response.text.isNotEmpty()) {
                send(AgentEvent.TextDelta(response.text))
            }
            response.usage?.let { send(AgentEvent.UsageReported(it)) }
            send(completedTurn(request, response))
        } finally {
            // Keep the warm session on cancellation (it is reused across turns and stopped/deleted in close()).
            // cancelAndJoin now returns promptly because streamSessionLogs closes its endless SSE stream on cancel.
            logJob.cancelAndJoin()
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(AgentEvent.Failed(error))
    }

    override suspend fun close() {
        try {
            val sessionId = stateMutex.withLock {
                currentSession.also { currentSession = null }?.sessionId
            }
            if (sessionId != null) {
                // Best-effort delete-only: the service rejects deleting a session that is concurrently being
                // stopped (409 invalid_request_error), and the SDK sample cleans up with deleteSession alone.
                // A cleanup failure must not mask a completed turn, so swallow it.
                runCatching { client.deleteSession(agentName, sessionId) }
            }
        } finally {
            client.close()
        }
    }

    private suspend fun ensureVersion(): HostedAgentVersion =
        stateMutex.withLock {
            selectedVersion ?: client.selectOrCreateAgentVersion(agentName, containerImage)
                .also {
                    client.configureResponsesEndpoint(agentName, it.version)
                    selectedVersion = it
                }
        }

    private suspend fun ensureSession(version: HostedAgentVersion, requestedSessionId: String?): HostedAgentSession =
        stateMutex.withLock {
            val existing = currentSession
            if (existing != null && (requestedSessionId == null || requestedSessionId == existing.sessionId)) {
                return@withLock existing
            }

            val resolved = if (requestedSessionId != null) {
                client.getSession(agentName, requestedSessionId)
            } else {
                client.createSession(agentName, version.version)
            }
            currentSession = resolved
            resolved
        }

    private fun completedTurn(
        request: TurnRequest,
        response: HostedAgentResponse,
    ): AgentEvent.TurnCompleted =
        AgentEvent.TurnCompleted(
            AssistantEntry(
                id = Uuid.random(),
                parentId = request.history.lastOrNull()?.id,
                timestamp = Clock.System.now(),
                text = response.text,
                usage = response.usage,
            ),
        )
}
