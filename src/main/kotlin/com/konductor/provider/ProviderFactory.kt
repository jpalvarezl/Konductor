package com.konductor.provider

import com.konductor.config.Configuration
import com.konductor.provider.hosted.HostedProvider
import com.konductor.provider.inference.EphemeralFoundryResponsesClient
import com.konductor.provider.inference.PromptAgentFoundryResponsesClient
import com.konductor.provider.inference.SwitchableFoundryResponsesClient

/** Builds the configured provider while keeping SDK construction inside each provider's SDK boundary. */
object ProviderFactory {
    fun create(configuration: Configuration): AgentProvider =
        when (configuration.agentKind) {
            // Select the Foundry Responses request-shape adapter from the active PromptAgent binding (M2.5): the
            // factory builds PromptAgentFoundryResponsesClient when an agent is named, otherwise the ephemeral
            // EphemeralFoundryResponsesClient. The two adapters stay separate because an agent rejects
            // model/instructions/tools in the request.
            AgentKind.Prompt -> PromptProvider(
                SwitchableFoundryResponsesClient(
                    factory = { agentName ->
                        if (agentName != null) PromptAgentFoundryResponsesClient(configuration, agentName)
                        else EphemeralFoundryResponsesClient(configuration)
                    },
                    initialAgent = configuration.promptAgentName,
                ),
                maxToolIterations = configuration.maxToolIterations,
            )
            AgentKind.Hosted -> HostedProvider(configuration)
        }
}
