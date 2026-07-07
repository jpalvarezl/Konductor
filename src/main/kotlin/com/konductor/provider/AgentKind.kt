package com.konductor.provider

/**
 * Which kind of agent backs a provider.
 *
 * This is a Konductor-level concept (not the Azure SDK's `AgentKind`): the provider seam is
 * intentionally decoupled from the SDK, and Azure-backed implementations map to/from SDK types
 * internally.
 */
enum class AgentKind {
    /** Client-owned Prompt loop driven by the harness (`PromptProvider`). */
    Prompt,

    /** Server-owned loop running inside a hosted agent container (`HostedProvider`). */
    Hosted,
}
