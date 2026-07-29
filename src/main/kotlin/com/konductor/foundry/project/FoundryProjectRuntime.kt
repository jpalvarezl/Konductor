package com.konductor.foundry.project

import com.azure.ai.agents.AgentsClientBuilder
import com.azure.ai.projects.AIProjectClientBuilder
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import com.konductor.foundry.project.connection.AzureFoundryConnectionCatalog
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.AzureFoundryDeploymentCatalog
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.provider.ProviderFactory
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.hosted.AzureHostedAgentClient
import com.konductor.provider.inference.AzurePromptAgentClient
import com.konductor.provider.inference.EphemeralFoundryResponsesClient
import com.konductor.provider.inference.PromptAgentFoundryResponsesClient

/**
 * Process-scoped composition boundary for one configured Azure AI Foundry project.
 *
 * The finite public surface is intentionally limited to the two project catalogs and per-session provider creation;
 * this is not a general SDK client locator. Project catalog clients are shared because the generated synchronous
 * clients are process-safe and not closeable. Each [createProvider] call builds fresh Prompt/Hosted adapters so their
 * closeable OpenAI clients and binding/session state remain owned by that provider runtime.
 */
class FoundryProjectRuntime private constructor(
    private val projectEndpoint: String,
    private val tokenCredential: TokenCredential,
    val deployments: FoundryDeploymentCatalog,
    val connections: FoundryConnectionCatalog,
    private val providerFactory: (Configuration) -> ProviderRuntime,
) {
    /** Build one isolated provider runtime while retaining this project's endpoint, credential, and client policy. */
    fun createProvider(configuration: Configuration): ProviderRuntime {
        require(configuration.projectEndpoint == projectEndpoint) {
            "Provider configuration must use the Foundry project endpoint owned by this runtime."
        }
        // Session copies reuse the resolved credential instance. A future config reload must create a new project
        // runtime rather than mixing credentials inside this process-scoped composition.
        require(configuration.tokenCredential === tokenCredential) {
            "Provider configuration must use the Azure credential owned by this runtime."
        }
        return providerFactory(configuration)
    }

    companion object {
        fun create(configuration: Configuration): FoundryProjectRuntime =
            create(configuration, AzureFoundryProjectComponentsFactory)

        internal fun create(
            configuration: Configuration,
            componentsFactory: FoundryProjectComponentsFactory,
        ): FoundryProjectRuntime {
            val components = componentsFactory.create(configuration.projectEndpoint, configuration.tokenCredential)
            return FoundryProjectRuntime(
                projectEndpoint = configuration.projectEndpoint,
                tokenCredential = configuration.tokenCredential,
                deployments = components.deployments,
                connections = components.connections,
                providerFactory = components.providerFactory,
            )
        }
    }
}

/** Pure injection seam for deterministic composition tests; production uses the Azure SDK implementation below. */
internal fun interface FoundryProjectComponentsFactory {
    fun create(projectEndpoint: String, tokenCredential: TokenCredential): FoundryProjectComponents
}

internal data class FoundryProjectComponents(
    val deployments: FoundryDeploymentCatalog,
    val connections: FoundryConnectionCatalog,
    val providerFactory: (Configuration) -> ProviderRuntime,
)

/** The sole production owner of Azure Projects/Agents builder, endpoint, credential, and preview policy. */
private object AzureFoundryProjectComponentsFactory : FoundryProjectComponentsFactory {
    override fun create(projectEndpoint: String, tokenCredential: TokenCredential): FoundryProjectComponents {
        val projectBuilder = AIProjectClientBuilder()
            .endpoint(projectEndpoint)
            .credential(tokenCredential)
        val deployments = AzureFoundryDeploymentCatalog(projectBuilder.buildDeploymentsClient())
        val connections = AzureFoundryConnectionCatalog(projectBuilder.buildConnectionsClient())

        fun agentsBuilder(allowPreview: Boolean = false): AgentsClientBuilder =
            AgentsClientBuilder()
                .endpoint(projectEndpoint)
                .credential(tokenCredential)
                .also { if (allowPreview) it.allowPreview(true) }

        val providers = ProviderFactory(
            ephemeralResponsesClient = {
                EphemeralFoundryResponsesClient(agentsBuilder().buildOpenAIClient())
            },
            promptAgentResponsesClient = { agentName ->
                PromptAgentFoundryResponsesClient(
                    agentsBuilder(allowPreview = true).buildAgentScopedOpenAIClient(agentName),
                )
            },
            promptAgentClient = {
                AzurePromptAgentClient(agentsBuilder().buildAgentsClient())
            },
            hostedAgentClient = { agentName ->
                val builder = agentsBuilder(allowPreview = true)
                AzureHostedAgentClient(
                    agentsClient = builder.buildAgentsClient(),
                    openAIClient = builder.buildAgentScopedOpenAIClient(agentName),
                )
            },
        )
        return FoundryProjectComponents(deployments, connections, providers::create)
    }
}
