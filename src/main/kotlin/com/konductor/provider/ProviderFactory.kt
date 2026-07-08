package com.konductor.provider

import com.konductor.config.Configuration
import com.konductor.provider.hosted.HostedProvider
import com.konductor.provider.inference.AzureInferenceClient
import com.konductor.provider.inference.AzurePromptAgentInferenceClient
import com.konductor.provider.inference.SwappableInferenceClient

/** Builds the configured provider while keeping SDK construction inside each provider's SDK boundary. */
object ProviderFactory {
    fun create(configuration: Configuration): AgentProvider =
        when (configuration.agentKind) {
            // The Prompt inference client is wrapped so the bound PromptAgent (M2.5) can be hot-swapped: the
            // factory builds the agent-scoped AzurePromptAgentInferenceClient when an agent is named, else the
            // plain ephemeral AzureInferenceClient. Two impls (not one branching class) — they send different
            // request shapes (an agent rejects model/instructions/tools).
            AgentKind.Prompt -> PromptProvider(
                SwappableInferenceClient(
                    factory = { agentName ->
                        if (agentName != null) AzurePromptAgentInferenceClient(configuration, agentName)
                        else AzureInferenceClient(configuration)
                    },
                    initialAgent = configuration.promptAgentName,
                ),
                maxToolIterations = configuration.maxToolIterations,
            )
            AgentKind.Hosted -> HostedProvider(configuration)
        }
}
