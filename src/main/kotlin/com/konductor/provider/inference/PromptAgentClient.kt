package com.konductor.provider.inference

import com.konductor.core.models.ToolSpec

/**
 * Lifecycle client for persisted Foundry **PromptAgents** (M2.5) — a standalone management surface for creating
 * and listing agent versions, mirroring the `HostedAgentClient` seam. Deliberately **separate from inference**:
 * binding an agent for a turn is done by pointing the Responses client at it (`buildAgentScopedOpenAIClient`),
 * not through this client.
 */
interface PromptAgentClient {
    /** The persisted PromptAgent names in the configured Foundry project (for `/agent list`). */
    suspend fun listAgents(): List<String>

    /**
     * Create a new version of [name] from the given definition (model + instructions + tool declarations) and
     * return the resolved reference. Used by `/agent create` to mint an agent from the current agent context.
     */
    suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef
}

/** A resolved persisted-agent reference: [name] plus the concrete [version] the service assigned. */
data class PromptAgentRef(val name: String, val version: String)

