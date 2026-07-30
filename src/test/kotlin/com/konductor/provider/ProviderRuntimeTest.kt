package com.konductor.provider

import com.konductor.provider.hosted.HostedAgentClient
import com.konductor.provider.hosted.HostedAgentResponse
import com.konductor.provider.hosted.HostedAgentSession
import com.konductor.provider.hosted.HostedAgentVersion
import com.konductor.provider.hosted.HostedProvider
import com.konductor.provider.inference.FoundryResponsesClient
import com.konductor.provider.inference.FoundryResponsesEvent
import com.konductor.provider.inference.FoundryResponsesRequest
import com.konductor.provider.inference.FoundryResponsesResult
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderRuntimeTest {
    @Test
    fun `Prompt runtime declares client-owned behavior and typed PromptAgent management`() {
        val responses = BindingResponsesClient()
        val provider = PromptProvider(responses)
        val management = ProviderManagement.PromptAgents(responses, NoOpPromptAgentClient)
        val runtime = ProviderRuntime(provider, management)

        assertTrue(runtime.capabilities.clientCompaction)
        assertTrue(runtime.capabilities.clientModelSwitching)
        assertTrue(runtime.capabilities.localTools)
        assertTrue(runtime.capabilities.promptAgentManagement)
        assertEquals(SessionHistoryOwnership.Client, runtime.capabilities.sessionHistoryOwnership)
        assertIs<ProviderManagement.PromptAgents>(runtime.management)
    }

    @Test
    fun `binder-capable Prompt provider remains usable without an attached management surface`() {
        val runtime = ProviderRuntime(PromptProvider(BindingResponsesClient()))

        assertFalse(runtime.capabilities.promptAgentManagement)
        assertEquals(ProviderManagement.None, runtime.management)
    }

    @Test
    fun `Hosted runtime declares server-owned behavior without client management`() {
        val runtime = ProviderRuntime(
            HostedProvider(NoOpHostedAgentClient, agentName = "hosted", containerImage = "image"),
        )

        assertFalse(runtime.capabilities.clientCompaction)
        assertFalse(runtime.capabilities.clientModelSwitching)
        assertFalse(runtime.capabilities.localTools)
        assertFalse(runtime.capabilities.promptAgentManagement)
        assertEquals(SessionHistoryOwnership.Server, runtime.capabilities.sessionHistoryOwnership)
        assertEquals(ProviderManagement.None, runtime.management)
        assertIs<ProviderSessionLifecycle.Hosted>(runtime.sessionLifecycle)
    }
}

private class BindingResponsesClient : FoundryResponsesClient, PromptAgentBinder {
    override var activeAgent: String? = null
        private set

    override fun bindAgent(agentName: String?) {
        activeAgent = agentName?.trim()?.ifBlank { null }
    }

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")
    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = emptyFlow()
    override suspend fun close() = Unit
}

private object NoOpPromptAgentClient : PromptAgentClient {
    override suspend fun listAgents(): List<String> = emptyList()

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef = PromptAgentRef(name, "1")
}

private object NoOpHostedAgentClient : HostedAgentClient {
    override suspend fun selectOrCreateAgentVersion(agentName: String, containerImage: String): HostedAgentVersion =
        HostedAgentVersion("1")

    override suspend fun configureResponsesEndpoint(agentName: String, version: String) = Unit
    override suspend fun createSession(agentName: String, version: String): HostedAgentSession =
        HostedAgentSession("s", version)
    override suspend fun createSession(
        agentName: String,
        version: String,
        sessionId: String,
    ): HostedAgentSession = HostedAgentSession(sessionId, version)
    override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession =
        HostedAgentSession(sessionId, "1")
    override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse =
        HostedAgentResponse("ok")

    override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> = emptyFlow()
    override suspend fun deleteSession(agentName: String, sessionId: String) = Unit
    override suspend fun close() = Unit
}
