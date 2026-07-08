package com.konductor.provider

import com.konductor.config.Configuration
import com.konductor.provider.hosted.HostedProvider
import com.konductor.provider.inference.AzureInferenceClient

/** Builds the configured provider while keeping SDK construction inside each provider's SDK boundary. */
object ProviderFactory {
    fun create(configuration: Configuration): AgentProvider =
        when (configuration.agentKind) {
            AgentKind.Prompt -> PromptProvider(AzureInferenceClient(configuration))
            AgentKind.Hosted -> HostedProvider(configuration)
        }
}
