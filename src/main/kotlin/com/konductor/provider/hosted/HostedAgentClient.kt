package com.konductor.provider.hosted

import com.konductor.core.models.Usage
import kotlinx.coroutines.flow.Flow

/** Foundry Hosted-agent SDK seam used by [HostedProvider] to isolate service calls for deterministic tests. */
interface HostedAgentClient {
    suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion
    suspend fun configureResponsesEndpoint(agentName: String, version: String)
    suspend fun createSession(agentName: String, version: String): HostedAgentSession
    suspend fun createSession(agentName: String, version: String, sessionId: String): HostedAgentSession
    suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession
    suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse
    fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String>
    suspend fun deleteSession(agentName: String, sessionId: String)
    suspend fun close()
}

data class HostedAgentVersion(val version: String)

enum class HostedAgentSessionStatus {
    Creating,
    Active,
    Idle,
    Updating,
    Failed,
    Deleting,
    Deleted,
    Expired,
    Unknown,
}

data class HostedAgentSession(
    val sessionId: String,
    val version: String? = null,
    val status: HostedAgentSessionStatus = HostedAgentSessionStatus.Active,
)

/** A scoped get proved that the configured agent has no session with the requested id. */
class HostedSessionNotFoundException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Deterministic create returned HTTP 409; the exact id must be reconciled with getSession. */
class HostedSessionCreateConflictException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Deterministic create may have committed but did not return a usable response. */
class HostedSessionCreateAmbiguousException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class HostedAgentResponse(
    val text: String,
    val usage: Usage? = null,
)
