package com.konductor.provider.inference

/**
 * The live agent-binding control surface for the Prompt provider (M2.5): report and hot-swap the persisted
 * PromptAgent the current session's turns are routed through. Implemented by [SwitchableFoundryResponsesClient], which
 * rebuilds the underlying Foundry Responses adapter on [bindAgent] rather than mutating an SDK client. Kept separate
 * from the agent *lifecycle* ([PromptAgentClient]).
 */
interface PromptAgentBinder {
    /** The persisted agent the next turn will reference, or `null` for the ephemeral (default) path. */
    val activeAgent: String?

    /**
     * Bind subsequent turns through the SDK's name-scoped persisted-agent endpoint, or unbind with `null`/blank. Safe to
     * call between turns (the TUI runs turns synchronously, so none is in flight while `/agent` is handled).
     */
    fun bindAgent(agentName: String?)
}
