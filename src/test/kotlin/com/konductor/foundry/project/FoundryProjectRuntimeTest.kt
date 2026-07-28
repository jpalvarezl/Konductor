package com.konductor.foundry.project

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import com.konductor.foundry.project.connection.AzureFoundryConnectionCatalog
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.AzureFoundryDeploymentCatalog
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.provider.PromptProvider
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.inference.FoundryResponsesClient
import com.konductor.provider.inference.FoundryResponsesEvent
import com.konductor.provider.inference.FoundryResponsesRequest
import com.konductor.provider.inference.FoundryResponsesResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class FoundryProjectRuntimeTest {
    private val credential = TokenCredential { _ ->
        Mono.just(AccessToken("fake-token", OffsetDateTime.parse("2099-01-01T00:00:00Z")))
    }

    @Test
    fun `production composition constructs both GA project catalogs and isolated providers offline`() {
        val configuration = configuration()
        val project = FoundryProjectRuntime.create(configuration)

        assertIs<AzureFoundryDeploymentCatalog>(project.deployments)
        assertIs<AzureFoundryConnectionCatalog>(project.connections)

        val first = project.createProvider(configuration)
        val second = project.createProvider(configuration.copy(model = "another-deployment"))
        assertNotSame(first.provider, second.provider)

        runBlocking {
            first.close()
            second.close()
        }
    }

    @Test
    fun `composition forwards resolved project identity once and preserves typed catalog instances`() {
        val deployments = FakeDeploymentCatalog
        val connections = FakeConnectionCatalog
        val providerConfigurations = mutableListOf<Configuration>()
        var factoryCalls = 0
        var receivedEndpoint: String? = null
        var receivedCredential: TokenCredential? = null
        val componentsFactory = FoundryProjectComponentsFactory { endpoint, tokenCredential ->
            factoryCalls += 1
            receivedEndpoint = endpoint
            receivedCredential = tokenCredential
            FoundryProjectComponents(deployments, connections) { providerConfiguration ->
                providerConfigurations += providerConfiguration
                ProviderRuntime(PromptProvider(NoOpResponsesClient()))
            }
        }
        val configuration = configuration()

        val project = FoundryProjectRuntime.create(configuration, componentsFactory)
        val sessionConfiguration = configuration.copy(model = "session-deployment", promptAgentName = "session-agent")
        project.createProvider(sessionConfiguration)

        assertEquals(1, factoryCalls)
        assertEquals(configuration.projectEndpoint, receivedEndpoint)
        assertSame(credential, receivedCredential)
        assertSame(deployments, project.deployments)
        assertSame(connections, project.connections)
        assertEquals(listOf(sessionConfiguration), providerConfigurations)
    }

    @Test
    fun `provider creation cannot escape the project endpoint or credential`() {
        val project = FoundryProjectRuntime.create(
            configuration(),
            FoundryProjectComponentsFactory { _, _ ->
                FoundryProjectComponents(FakeDeploymentCatalog, FakeConnectionCatalog) {
                    ProviderRuntime(PromptProvider(NoOpResponsesClient()))
                }
            },
        )

        assertFailsWith<IllegalArgumentException> {
            project.createProvider(configuration().copy(projectEndpoint = "https://other.example/api/projects/p"))
        }
        assertFailsWith<IllegalArgumentException> {
            project.createProvider(configuration().copy(tokenCredential = TokenCredential { Mono.empty() }))
        }
    }

    private fun configuration(): Configuration = Configuration(
        projectEndpoint = "https://example.ai.azure.com/api/projects/project",
        tokenCredential = credential,
        model = "gpt-test",
    )
}

private object FakeDeploymentCatalog : FoundryDeploymentCatalog {
    override fun listDeployments(): List<FoundryDeployment> = emptyList()
    override fun getDeployment(name: String): FoundryDeployment = error("unused")
}

private object FakeConnectionCatalog : FoundryConnectionCatalog {
    override fun listConnections(): List<FoundryConnection> = emptyList()
    override fun getConnection(name: String): FoundryConnection = error("unused")
}

private class NoOpResponsesClient : FoundryResponsesClient {
    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = error("unused")
    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = emptyFlow()
    override suspend fun close() = Unit
}
