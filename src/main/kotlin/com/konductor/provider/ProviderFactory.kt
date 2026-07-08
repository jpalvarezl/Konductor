package com.konductor.provider

import com.konductor.config.Configuration
import com.konductor.provider.hosted.HostedProvider
import com.konductor.provider.inference.AzureInferenceClient
import com.konductor.provider.inference.SwappableInferenceClient

/** Builds the configured provider while keeping SDK construction inside each provider's SDK boundary. */
object ProviderFactory {
    fun create(configuration: Configuration): AgentProvider =
        when (configuration.agentKind) {
            // The Prompt inference client is wrapped so the bound PromptAgent (M2.5) can be hot-swapped by
            // rebuilding an agent-scoped AzureInferenceClient, leaving that class agent-agnostic.
            AgentKind.Prompt -> PromptProvider(
                SwappableInferenceClient(
                    factory = { agentName -> AzureInferenceClient(configuration, agentName) },
                    initialAgent = configuration.promptAgentName,
                ),
                maxToolIterations = configuration.maxToolIterations,
            )
            AgentKind.Hosted -> HostedProvider(configuration)
        }
}
