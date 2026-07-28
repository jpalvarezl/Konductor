package com.konductor.provider

import com.konductor.config.Configuration
import com.konductor.provider.hosted.HostedProvider
import com.konductor.provider.inference.AzurePromptAgentClient
import com.konductor.provider.inference.EphemeralFoundryResponsesClient
import com.konductor.provider.inference.PromptAgentFoundryResponsesClient
import com.konductor.provider.inference.SwitchableFoundryResponsesClient

/** Builds a configured Foundry runtime while keeping SDK construction inside each provider's SDK boundary. */
object ProviderFactory {
    /** Configuration-time capabilities for CLI validation before any SDK clients are constructed. */
    fun capabilities(configuration: Configuration): ProviderCapabilities =
        when (configuration.agentKind) {
            AgentKind.Prompt -> ProviderCapabilities.prompt(promptAgentManagement = true)
            AgentKind.Hosted -> ProviderCapabilities.Hosted
        }

    fun create(configuration: Configuration): ProviderRuntime =
        when (configuration.agentKind) {
            // Select the Foundry Responses request-shape adapter from the active PromptAgent binding (M2.5): the
            // factory builds PromptAgentFoundryResponsesClient when an agent is named, otherwise the ephemeral
            // EphemeralFoundryResponsesClient. The two adapters stay separate because an agent rejects
            // model/instructions/tools in the request.
            AgentKind.Prompt -> {
                val responsesClient = SwitchableFoundryResponsesClient(
                    factory = { agentName ->
                        if (agentName != null) PromptAgentFoundryResponsesClient(configuration, agentName)
                        else EphemeralFoundryResponsesClient(configuration)
                    },
                    initialAgent = configuration.promptAgentName,
                )
                ProviderRuntime(
                    provider = PromptProvider(
                        responsesClient,
                        maxToolIterations = configuration.maxToolIterations,
                    ),
                    management = ProviderManagement.PromptAgents(
                        binder = responsesClient,
                        lifecycle = AzurePromptAgentClient(configuration),
                    ),
                )
            }
            AgentKind.Hosted -> ProviderRuntime(HostedProvider(configuration))
        }
}
