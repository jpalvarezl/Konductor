package com.konductor.provider.hosted

import com.konductor.core.models.Usage
import kotlinx.coroutines.flow.Flow

/** Neutral hosted-agent SDK seam used by [HostedProvider] so tests can run without live Azure resources. */
interface HostedAgentClient {
    suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion
    suspend fun configureResponsesEndpoint(agentName: String, version: String)
    suspend fun createSession(agentName: String, version: String): HostedAgentSession
    suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession
    suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse
    fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String>
    suspend fun stopSession(agentName: String, sessionId: String)
    suspend fun deleteSession(agentName: String, sessionId: String)
    suspend fun close()
}

data class HostedAgentVersion(val version: String)

data class HostedAgentSession(val sessionId: String)

data class HostedAgentResponse(
    val text: String,
    val usage: Usage? = null,
)
