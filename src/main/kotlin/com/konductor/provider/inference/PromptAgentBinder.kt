package com.konductor.provider.inference

/**
 * The live agent-binding control surface for the Prompt provider (M2.5): report and hot-swap the persisted
 * PromptAgent the current session's turns are routed through. Implemented by [SwappableInferenceClient], which
 * rebuilds the underlying agent-scoped [InferenceClient] on [bindAgent] — the inference logic itself is never
 * mutated. Kept separate from the agent *lifecycle* ([PromptAgentClient]).
 */
interface PromptAgentBinder {
    /** The persisted agent the next turn will reference, or `null` for the ephemeral (default) path. */
    val activeAgent: String?

    /**
     * Bind subsequent turns to the named persisted agent (latest version), or unbind with `null`/blank. Safe to
     * call between turns (the TUI runs turns synchronously, so none is in flight while `/agent` is handled).
     */
    fun bindAgent(agentName: String?)
}
