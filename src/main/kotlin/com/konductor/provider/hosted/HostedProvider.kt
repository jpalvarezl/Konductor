package com.konductor.provider.hosted

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.HostedSessionController
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Hosted execution plus the durable Foundry session lifecycle carried by [HostedSessionController]. */
class HostedProvider(
    private val client: HostedAgentClient,
    private val agentName: String,
    private val containerImage: String,
    private val sessionPollAttempts: Int = DEFAULT_SESSION_POLL_ATTEMPTS,
    private val sessionPollIntervalMs: Long = DEFAULT_SESSION_POLL_INTERVAL_MS,
) : AgentProvider, HostedSessionController {
    override val hostedAgentName: String get() = agentName
    override val kind: AgentKind = AgentKind.Hosted
    override val capabilities: ProviderCapabilities = ProviderCapabilities.Hosted

    private val stateMutex = Mutex()
    private var selectedVersion: HostedAgentVersion? = null
    private var activeSession: ActiveSession? = null

    init {
        require(hostedAgentName.isNotBlank()) { "Hosted agent name cannot be blank." }
        require(sessionPollAttempts > 0) { "Session poll attempts must be positive." }
        require(sessionPollIntervalMs >= 0) { "Session poll interval cannot be negative." }
    }

    override suspend fun activate(binding: HostedSessionBinding?, hasLocalEntries: Boolean) {
        stateMutex.withLock {
            if (binding != null && binding.agentName != hostedAgentName) {
                error(
                    "Hosted session is bound to agent '${binding.agentName}', but this runtime is configured for " +
                        "'$hostedAgentName'. Reconfigure that agent or start a new Hosted session.",
                )
            }

            val existing = activeSession
            if (binding == null && existing?.ownership == Ownership.Ephemeral) return
            if (binding != null && existing?.ownership == Ownership.Durable &&
                existing.resource.sessionId == binding.sessionId
            ) {
                return
            }

            val resolved = if (binding == null) {
                createEphemeralLocked()
            } else {
                resolveDurableLocked(binding, hasLocalEntries)
            }
            val replacement = ActiveSession(
                resource = resolved,
                ownership = if (binding == null) Ownership.Ephemeral else Ownership.Durable,
            )

            // Only runtime-owned ephemeral resources are deleted. A durable binding remains owned by its JSONL
            // session when another local session becomes active.
            if (existing?.ownership == Ownership.Ephemeral && existing.resource.sessionId != resolved.sessionId) {
                runCatching { client.deleteSession(hostedAgentName, existing.resource.sessionId) }
            }
            activeSession = replacement
        }
    }

    override suspend fun detach() {
        stateMutex.withLock {
            val detached = activeSession.also { activeSession = null }
            if (detached?.ownership == Ownership.Ephemeral) {
                // Delete only; never stop-then-delete. Cleanup is best effort and must not mask completed work.
                runCatching { client.deleteSession(hostedAgentName, detached.resource.sessionId) }
            }
        }
    }

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = channelFlow {
        val session = stateMutex.withLock {
            activeSession?.resource ?: error(
                "Hosted session is not activated. Activate the selected local session before running a turn.",
            )
        }
        val version = requireNotNull(session.version?.takeIf { it.isNotBlank() }) {
            "Hosted session '${session.sessionId}' did not return a version_ref agent version."
        }
        val logJob = launch {
            client.streamSessionLogs(hostedAgentName, version, session.sessionId).collect { line ->
                send(AgentEvent.LogFrame(line))
            }
        }

        try {
            val userText = request.history.filterIsInstance<UserEntry>().lastOrNull()?.text
                ?: error("HostedProvider requires a user entry in the turn history.")
            val response = client.invoke(hostedAgentName, session.sessionId, userText)
            if (response.text.isNotEmpty()) send(AgentEvent.TextDelta(response.text))
            response.usage?.let { send(AgentEvent.UsageReported(it)) }
            send(completedTurn(request, response))
        } finally {
            // Cancellation closes local log collection but deliberately preserves the remote binding.
            logJob.cancelAndJoin()
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(AgentEvent.Failed(error))
    }

    override suspend fun close() {
        try {
            detach()
        } finally {
            client.close()
        }
    }

    private suspend fun createEphemeralLocked(): HostedAgentSession {
        val version = ensureVersionLocked()
        return awaitUsable(client.createSession(hostedAgentName, version.version))
    }

    private suspend fun resolveDurableLocked(
        binding: HostedSessionBinding,
        hasLocalEntries: Boolean,
    ): HostedAgentSession {
        val existing = try {
            client.getSession(hostedAgentName, binding.sessionId)
        } catch (_: HostedSessionNotFoundException) {
            null
        }
        if (existing != null) return awaitUsable(requireExpectedId(existing, binding.sessionId))

        if (hasLocalEntries) {
            error(
                "Hosted session '${binding.sessionId}' is missing for agent '$hostedAgentName'. Its server-owned " +
                    "history cannot be replaced or reconstructed from the local transcript; start a new session.",
            )
        }

        val version = ensureVersionLocked()
        return try {
            awaitUsable(
                requireExpectedId(
                    client.createSession(hostedAgentName, version.version, binding.sessionId),
                    binding.sessionId,
                ),
            )
        } catch (failure: HostedSessionCreateConflictException) {
            reconcileCreateLocked(binding.sessionId, failure)
        } catch (failure: HostedSessionCreateAmbiguousException) {
            reconcileCreateLocked(binding.sessionId, failure)
        }
    }

    private suspend fun reconcileCreateLocked(sessionId: String, createFailure: RuntimeException): HostedAgentSession {
        repeat(sessionPollAttempts) { attempt ->
            val resource = try {
                client.getSession(hostedAgentName, sessionId)
            } catch (_: HostedSessionNotFoundException) {
                null
            }
            if (resource != null) return awaitUsable(requireExpectedId(resource, sessionId))
            if (attempt < sessionPollAttempts - 1) delay(sessionPollIntervalMs)
        }
        throw IllegalStateException(
            "Could not reconcile Hosted session '$sessionId' after an ambiguous or conflicting create. " +
                "No replacement was created; retry to reconnect the reserved binding.",
            createFailure,
        )
    }

    private fun requireExpectedId(resource: HostedAgentSession, expectedId: String): HostedAgentSession {
        require(resource.sessionId == expectedId) {
            "Hosted service returned session '${resource.sessionId}' for requested binding '$expectedId'."
        }
        return resource
    }

    private suspend fun awaitUsable(initial: HostedAgentSession): HostedAgentSession {
        var resource = initial
        repeat(sessionPollAttempts) { attempt ->
            require(resource.sessionId.isNotBlank()) { "Hosted service returned a blank session id." }
            require(!resource.version.isNullOrBlank()) {
                "Hosted session '${resource.sessionId}' did not return a version_ref agent version."
            }
            when (resource.status) {
                HostedAgentSessionStatus.Active,
                HostedAgentSessionStatus.Idle,
                -> return resource

                HostedAgentSessionStatus.Creating,
                HostedAgentSessionStatus.Updating,
                -> {
                    if (attempt == sessionPollAttempts - 1) {
                        error(
                            "Timed out waiting for Hosted session '${resource.sessionId}' to leave " +
                                "${resource.status.name.uppercase()} after $sessionPollAttempts checks.",
                        )
                    }
                    delay(sessionPollIntervalMs)
                    resource = try {
                        client.getSession(hostedAgentName, resource.sessionId)
                    } catch (missing: HostedSessionNotFoundException) {
                        throw IllegalStateException(
                            "Hosted session '${resource.sessionId}' disappeared while " +
                                "${resource.status.name.lowercase()}.",
                            missing,
                        )
                    }
                }

                HostedAgentSessionStatus.Failed,
                HostedAgentSessionStatus.Deleting,
                HostedAgentSessionStatus.Deleted,
                HostedAgentSessionStatus.Expired,
                -> error(
                    "Hosted session '${resource.sessionId}' is ${resource.status.name.uppercase()} and cannot be " +
                        "resumed. Start a new session.",
                )

                HostedAgentSessionStatus.Unknown -> error(
                    "Hosted session '${resource.sessionId}' returned an unknown status and cannot be used safely.",
                )
            }
        }
        error("unreachable Hosted session polling state")
    }

    private suspend fun ensureVersionLocked(): HostedAgentVersion {
        val existing = selectedVersion
        if (existing != null) return existing
        return client.selectOrCreateAgentVersion(hostedAgentName, containerImage).also {
            client.configureResponsesEndpoint(hostedAgentName, it.version)
            selectedVersion = it
        }
    }

    private fun completedTurn(request: TurnRequest, response: HostedAgentResponse): AgentEvent.TurnCompleted =
        AgentEvent.TurnCompleted(
            AssistantEntry(
                id = Uuid.random(),
                parentId = request.history.lastOrNull()?.id,
                timestamp = Clock.System.now(),
                text = response.text,
                usage = response.usage,
            ),
        )

    private data class ActiveSession(val resource: HostedAgentSession, val ownership: Ownership)

    private enum class Ownership { Durable, Ephemeral }

    private companion object {
        const val DEFAULT_SESSION_POLL_ATTEMPTS = 30
        const val DEFAULT_SESSION_POLL_INTERVAL_MS = 2_000L
    }
}
