package com.konductor.provider.hosted

import com.azure.ai.agents.AgentsClient
import com.azure.ai.agents.models.AgentEndpointConfig
import com.azure.ai.agents.models.AgentEndpointProtocol
import com.azure.ai.agents.models.AgentSessionResource
import com.azure.ai.agents.models.AgentSessionStatus
import com.azure.ai.agents.models.AgentVersionStatus
import com.azure.ai.agents.models.ContainerConfiguration
import com.azure.ai.agents.models.FixedRatioVersionSelectionRule
import com.azure.ai.agents.models.HostedAgentDefinition
import com.azure.ai.agents.models.PageOrder
import com.azure.ai.agents.models.ProtocolConfiguration
import com.azure.ai.agents.models.ProtocolVersionRecord
import com.azure.ai.agents.models.ResponsesProtocolConfiguration
import com.azure.ai.agents.models.UpdateAgentDetailsOptions
import com.azure.ai.agents.models.VersionRefIndicator
import com.azure.ai.agents.models.VersionSelector
import com.azure.core.exception.HttpResponseException
import com.azure.core.exception.ResourceModifiedException
import com.azure.core.exception.ResourceNotFoundException
import com.azure.core.http.rest.RequestOptions
import com.konductor.core.models.Usage
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * Hosted-agent SDK boundary. The project composition supplies preview-enabled Agents and agent-scoped OpenAI
 * clients; all hosted-session calls live here so the provider remains unit-testable with [HostedAgentClient] fakes.
 *
 * The version/session/endpoint methods are `suspend` + confined to [Dispatchers.IO] (blocking SDK calls), and
 * mirror the SDK's `hostedagents` samples: a created version is provisioned asynchronously, so we **poll**
 * `getAgentVersionDetails` until `ACTIVE`; the definition sets the RESPONSES protocol version and the
 * `enableVnextExperience` metadata the samples require. The session log stream is an endless server-sent-event
 * stream the client must terminate itself, so [streamSessionLogs] reads via `getSessionLogStreamWithResponse`
 * and **closes the underlying stream on cancellation** to break the otherwise-uninterruptible blocking read.
 */
class AzureHostedAgentClient(
    private val agentsClient: AgentsClient,
    private val openAIClient: OpenAIClient,
) : HostedAgentClient {

    override suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion {
        val existing = withContext(Dispatchers.IO) {
            agentsClient.listAgentVersions(agentName, 1, PageOrder.DESC, null, null, false)
                .firstOrNull { it.status == null || it.status == AgentVersionStatus.ACTIVE }
        }
        if (existing != null) return HostedAgentVersion(existing.version)

        val definition = HostedAgentDefinition(CPU, MEMORY)
            .setContainerConfiguration(ContainerConfiguration(containerImage))
            .setProtocolVersions(listOf(ProtocolVersionRecord(AgentEndpointProtocol.RESPONSES, PROTOCOL_VERSION)))
        val created = withContext(Dispatchers.IO) {
            agentsClient.createAgentVersion(
                agentName,
                definition,
                mapOf("enableVnextExperience" to "true"),
                "Konductor hosted coding agent",
            )
        }
        awaitVersionActive(agentName, created.version)
        return HostedAgentVersion(created.version)
    }

    /** Poll until the freshly-created version is ACTIVE — the service has no create-and-wait convenience. */
    private suspend fun awaitVersionActive(agentName: String, version: String) {
        repeat(MAX_POLL_ATTEMPTS) {
            val status = withContext(Dispatchers.IO) { agentsClient.getAgentVersionDetails(agentName, version).status }
            when (status) {
                AgentVersionStatus.ACTIVE -> return
                AgentVersionStatus.FAILED -> error("Hosted agent version provisioning failed: $version")
                else -> delay(POLL_INTERVAL_MS)
            }
        }
        error("Timed out waiting for hosted agent version to become active: $version")
    }

    override suspend fun configureResponsesEndpoint(agentName: String, version: String) {
        val endpointConfig = AgentEndpointConfig()
            .setVersionSelector(
                VersionSelector().setVersionSelectionRules(
                    listOf(FixedRatioVersionSelectionRule(100).setAgentVersion(version)),
                ),
            )
            .setProtocolConfiguration(ProtocolConfiguration().setResponses(ResponsesProtocolConfiguration()))

        withContext(Dispatchers.IO) {
            agentsClient.updateAgentDetails(agentName, UpdateAgentDetailsOptions().setAgentEndpoint(endpointConfig))
        }
    }

    override suspend fun createSession(agentName: String, version: String): HostedAgentSession =
        withContext(Dispatchers.IO) {
            agentsClient.createSession(agentName, VersionRefIndicator(version)).toHostedSession()
        }

    override suspend fun createSession(
        agentName: String,
        version: String,
        sessionId: String,
    ): HostedAgentSession = try {
        withContext(Dispatchers.IO) {
            agentsClient.createSession(agentName, VersionRefIndicator(version), sessionId).toHostedSession()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (conflict: ResourceModifiedException) {
        throw HostedSessionCreateConflictException(
            "Hosted session '$sessionId' already exists or is being modified.",
            conflict,
        )
    } catch (error: HttpResponseException) {
        val status = error.response?.statusCode
        if (status == 408 || status == 429 || status != null && status >= 500) {
            throw HostedSessionCreateAmbiguousException(
                "Hosted session '$sessionId' create returned HTTP $status and may have committed.",
                error,
            )
        }
        throw error
    } catch (error: RuntimeException) {
        // The generated sync API documents transport failures only as RuntimeException. With a deterministic id,
        // reconcile rather than retry POST and risk misclassifying a create whose response was lost.
        throw HostedSessionCreateAmbiguousException(
            "Hosted session '$sessionId' create result was ambiguous.",
            error,
        )
    }

    override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession = try {
        withContext(Dispatchers.IO) {
            agentsClient.getSession(agentName, sessionId).toHostedSession()
        }
    } catch (missing: ResourceNotFoundException) {
        throw HostedSessionNotFoundException(
            "Hosted session '$sessionId' was not found for agent '$agentName'.",
            missing,
        )
    }

    override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse =
        withContext(Dispatchers.IO) {
            toHostedResponse(
                openAIClient.responses().create(
                    ResponseCreateParams.builder()
                        .input(input)
                        .putAdditionalBodyProperty("agent_session_id", JsonValue.from(sessionId))
                        .build(),
                ),
            )
        }

    override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> = callbackFlow {
        // Endless SSE stream with no completion signal: the client owns termination. Read frames on an IO job
        // and close the underlying stream in awaitClose, so collector cancellation (turn end) unblocks the
        // otherwise-uninterruptible readLine and the job finishes promptly.
        val stream: InputStream = withContext(Dispatchers.IO) {
            agentsClient.getSessionLogStreamWithResponse(agentName, version, sessionId, RequestOptions())
                .value.toStream()
        }
        val reader = launch(Dispatchers.IO) {
            try {
                stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.startsWith(SSE_DATA_PREFIX)) {
                        val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                        if (data.isNotEmpty()) trySend(data)
                    }
                }
            } catch (_: IOException) {
                // Expected once the stream is closed to break the endless read — normal termination.
            } finally {
                close()
            }
        }
        awaitClose {
            reader.cancel()
            runCatching { stream.close() } // unblocks the parked readLine so the reader job ends
        }
    }

    override suspend fun deleteSession(agentName: String, sessionId: String) {
        withContext(Dispatchers.IO) { agentsClient.deleteSession(agentName, sessionId) }
    }

    override suspend fun close() {
        // AgentsClient is not Closeable; only the agent-scoped openai client owns disposable resources.
        withContext(Dispatchers.IO) { openAIClient.close() }
    }

    private fun AgentSessionResource.toHostedSession(): HostedAgentSession {
        val version = (versionIndicator as? VersionRefIndicator)?.agentVersion
        val mappedStatus = when (status) {
            AgentSessionStatus.CREATING -> HostedAgentSessionStatus.Creating
            AgentSessionStatus.ACTIVE -> HostedAgentSessionStatus.Active
            AgentSessionStatus.IDLE -> HostedAgentSessionStatus.Idle
            AgentSessionStatus.UPDATING -> HostedAgentSessionStatus.Updating
            AgentSessionStatus.FAILED -> HostedAgentSessionStatus.Failed
            AgentSessionStatus.DELETING -> HostedAgentSessionStatus.Deleting
            AgentSessionStatus.DELETED -> HostedAgentSessionStatus.Deleted
            AgentSessionStatus.EXPIRED -> HostedAgentSessionStatus.Expired
            else -> HostedAgentSessionStatus.Unknown
        }
        return HostedAgentSession(agentSessionId, version, mappedStatus)
    }

    private fun toHostedResponse(response: Response): HostedAgentResponse =
        HostedAgentResponse(text = extractText(response), usage = extractUsage(response))

    private fun extractText(response: Response): String = buildString {
        for (item in response.output()) {
            val outputMessage = item.message().orElse(null) ?: continue
            for (content in outputMessage.content()) {
                content.outputText().orElse(null)?.let { append(it.text()) }
            }
        }
    }

    private fun extractUsage(response: Response): Usage? =
        response.usage().orElse(null)?.let {
            Usage(
                inputTokens = it.inputTokens().toInt(),
                outputTokens = it.outputTokens().toInt(),
                totalTokens = it.totalTokens().toInt(),
            )
        }

    private companion object {
        const val CPU = "0.5"
        const val MEMORY = "1Gi"
        const val PROTOCOL_VERSION = "1.0.0"
        const val SSE_DATA_PREFIX = "data: "
        const val MAX_POLL_ATTEMPTS = 60
        const val POLL_INTERVAL_MS = 10_000L
    }
}
