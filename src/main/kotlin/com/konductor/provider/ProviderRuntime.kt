package com.konductor.provider

import com.konductor.core.models.HostedSessionBinding
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient

/** Which side owns the conversation state that is authoritative for the next model turn. */
enum class SessionHistoryOwnership {
    Client,
    Server,
}

/**
 * Behavioral contract for one configured Foundry provider runtime. These values are consumed by shared core
 * orchestration; frontends must not reconstruct them from [AgentKind].
 */
data class ProviderCapabilities(
    val clientCompaction: Boolean,
    val clientModelSwitching: Boolean,
    val localTools: Boolean,
    val promptAgentManagement: Boolean,
    val sessionHistoryOwnership: SessionHistoryOwnership,
) {
    companion object {
        fun prompt(promptAgentManagement: Boolean): ProviderCapabilities =
            ProviderCapabilities(
                clientCompaction = true,
                clientModelSwitching = true,
                localTools = true,
                promptAgentManagement = promptAgentManagement,
                sessionHistoryOwnership = SessionHistoryOwnership.Client,
            )

        val Hosted: ProviderCapabilities = ProviderCapabilities(
            clientCompaction = false,
            clientModelSwitching = false,
            localTools = false,
            promptAgentManagement = false,
            sessionHistoryOwnership = SessionHistoryOwnership.Server,
        )
    }
}

/** Foundry-specific Hosted session activation implemented only by the Hosted provider. */
interface HostedSessionController {
    val hostedAgentName: String

    /** Activate a durable binding, or a provider-owned ephemeral resource when [binding] is null. */
    suspend fun activate(binding: HostedSessionBinding?, hasLocalEntries: Boolean)

    /** Release the active association; only provider-owned ephemeral resources are deleted. */
    suspend fun detach()
}

/** Optional session lifecycle assembled alongside execution without adding nullable methods to every provider. */
sealed interface ProviderSessionLifecycle {
    data object None : ProviderSessionLifecycle

    data class Hosted(val controller: HostedSessionController) : ProviderSessionLifecycle
}

/** Optional management surfaces constructed alongside turn execution, without nullable methods on every provider. */
sealed interface ProviderManagement {
    data object None : ProviderManagement

    data class PromptAgents(
        val binder: PromptAgentBinder,
        val lifecycle: PromptAgentClient,
    ) : ProviderManagement
}

/**
 * Composition result for one Foundry agent kind. It keeps execution, behavioral capabilities, and optional management
 * together so TUI and ACP can consume the same explicit contract.
 */
data class ProviderRuntime(
    val provider: AgentProvider,
    val management: ProviderManagement = ProviderManagement.None,
    val sessionLifecycle: ProviderSessionLifecycle =
        (provider as? HostedSessionController)?.let(ProviderSessionLifecycle::Hosted)
            ?: ProviderSessionLifecycle.None,
) {
    val capabilities: ProviderCapabilities = provider.capabilities.copy(
        promptAgentManagement = management is ProviderManagement.PromptAgents,
    )

    init {
        require(
            management !is ProviderManagement.PromptAgents ||
                (provider.capabilities.localTools &&
                    provider.capabilities.clientCompaction &&
                    provider.capabilities.clientModelSwitching &&
                    provider.capabilities.sessionHistoryOwnership == SessionHistoryOwnership.Client),
        ) {
            "PromptAgent management requires a client-loop provider with client-owned history and local tools."
        }
    }

    suspend fun close() = provider.close()
}
