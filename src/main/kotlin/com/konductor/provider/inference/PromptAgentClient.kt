package com.konductor.provider.inference

import com.konductor.core.models.AgentContext

/**
 * The opt-in **persisted PromptAgent** capability (M2.5, docs/spec/providers.md#persisted-prompt-agents-promptagent).
 *
 * Implemented by the Prompt-provider inference boundary ([AzureInferenceClient]) so the TUI `/agent` command can
 * list, create, and switch the persisted agent the client-owned loop is bound to — while the transcript, tool
 * loop, and local execution stay client-side. Binding is a no-op-compatible superset of [InferenceClient]: an
 * unbound client behaves exactly like the ephemeral Prompt path.
 */
interface PromptAgentClient {
    /** The persisted agent the next turn will reference, or `null` for the ephemeral (default) path. */
    val activeAgentName: String?

    /**
     * Bind subsequent turns to the named persisted agent (latest version), or unbind with `null`/blank. Rebuilds
     * the underlying agent-scoped client; call between turns (the TUI runs turns synchronously, so no turn is in
     * flight while a `/agent` command is handled).
     */
    fun bindAgent(agentName: String?)

    /** The persisted PromptAgent names available in the configured Foundry project (for `/agent list`). */
    suspend fun listAgentNames(): List<String>

    /**
     * Mint a new version of [agentName] from [context] — baking the **stable** base prompt + tool declarations into
     * a `PromptAgentDefinition` (the dynamic preamble stays per-turn) — and return the created reference.
     */
    suspend fun createAgentVersion(agentName: String, context: AgentContext): PromptAgentRef
}

/** A resolved persisted-agent reference: [name] plus the concrete [version] the service assigned. */
data class PromptAgentRef(val name: String, val version: String)
