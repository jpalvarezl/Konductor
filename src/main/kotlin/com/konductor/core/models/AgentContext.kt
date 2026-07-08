package com.konductor.core.models

/**
 * Everything a model sees *before* the transcript. Assembled from system prompt, context files, tool registry, etc.
 *
 * The preamble is split into a **stable** and a **dynamic** part so an opt-in persisted PromptAgent
 * (M2.5, docs/spec/agent-context.md#persisted-agents-stable-vs-dynamic-preamble) can bake the stable part into
 * a frozen agent version while the dynamic part still rides each turn:
 * - [baseSystemPrompt] — the stable base coding-agent prompt (or its override). Baked into a PromptAgent.
 * - [dynamicPreamble] — everything project-local / time-varying that must stay current: the environment header
 *   (cwd/os/date), the configured `systemPromptAppend`, and (later) context files. Sent per turn as a leading
 *   developer input item when an agent is bound, rather than frozen into the agent version.
 *
 * Ephemeral runs (the default) collapse both back into a single [systemPrompt] sent as request `instructions`.
 *
 * @param baseSystemPrompt The stable base system prompt (bakeable into a persisted agent).
 * @param tools The list of tools available to the model.
 * @param modelName The name of the model to use.
 * @param temperature The temperature to use for the model, if applicable.
 * @param dynamicPreamble The per-turn dynamic preamble (env header, configured append, context files); may be empty.
 */
data class AgentContext(
    val baseSystemPrompt: String,
    val tools: List<ToolSpec>,
    val modelName: String,
    val temperature: Double? = null,
    val dynamicPreamble: String = "",
) {
    /** The full ephemeral instructions: the stable base prompt followed by the dynamic preamble. */
    val systemPrompt: String
        get() = if (dynamicPreamble.isBlank()) baseSystemPrompt else "$baseSystemPrompt\n\n$dynamicPreamble"
}
