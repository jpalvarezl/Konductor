package com.konductor.provider

import com.konductor.config.Configuration
import com.konductor.provider.hosted.HostedAgentClient
import com.konductor.provider.hosted.HostedProvider
import com.konductor.provider.inference.FoundryResponsesClient
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.SwitchableFoundryResponsesClient

/**
 * Selects and assembles one configured Foundry provider from focused SDK adapters supplied by the project composition
 * boundary. The client factories create fresh resources for every runtime and expose no Azure SDK types here.
 */
class ProviderFactory internal constructor(
    private val ephemeralResponsesClient: () -> FoundryResponsesClient,
    private val promptAgentResponsesClient: (String) -> FoundryResponsesClient,
    private val promptAgentClient: () -> PromptAgentClient,
    private val hostedAgentClient: (String) -> HostedAgentClient,
) {
    companion object {
        /** Configuration-time capabilities for CLI validation before any SDK clients are constructed. */
        fun capabilities(configuration: Configuration): ProviderCapabilities =
            when (configuration.agentKind) {
                AgentKind.Prompt -> ProviderCapabilities.prompt(promptAgentManagement = true)
                AgentKind.Hosted -> ProviderCapabilities.Hosted
            }
    }

    fun create(configuration: Configuration): ProviderRuntime =
        when (configuration.agentKind) {
            // Select the Foundry Responses request-shape adapter from the active PromptAgent binding (M2.5): the
            // factory builds PromptAgentFoundryResponsesClient when an agent is named, otherwise the ephemeral
            // EphemeralFoundryResponsesClient. The two adapters stay separate because an agent rejects
            // model/instructions/tools in the request.
            AgentKind.Prompt -> {
                // Build the generated, non-closeable management client before allocating the closeable Responses
                // client, so a builder failure cannot orphan an OpenAI client before a runtime owns it.
                val agentClient = promptAgentClient()
                val responsesClient = SwitchableFoundryResponsesClient(
                    factory = { agentName ->
                        if (agentName != null) promptAgentResponsesClient(agentName)
                        else ephemeralResponsesClient()
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
                        lifecycle = agentClient,
                    ),
                )
            }
            AgentKind.Hosted -> {
                val agentName = configuration.hostedAgentName
                    ?: throw IllegalArgumentException(
                        "Hosted provider requires ${Configuration.ENV_HOSTED_AGENT_NAME}.",
                    )
                val containerImage = configuration.hostedAgentContainerImage
                    ?: throw IllegalArgumentException(
                        "Hosted provider requires ${Configuration.ENV_AGENT_CONTAINER_IMAGE}.",
                    )
                ProviderRuntime(HostedProvider(hostedAgentClient(agentName), agentName, containerImage))
            }
        }
}
