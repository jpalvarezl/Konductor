package com.konductor.core.models

/**
 * Everything a model sees *before* the transcript. Assembled from system prompt, context files, tool registry, etc.
 *
 * The prompt is exposed both whole ([systemPrompt], for the ephemeral path) and split for a persisted PromptAgent
 * (M2.5): [baseSystemPrompt] is the **stable** part baked into the agent version, and [dynamicPreamble] is the
 * per-turn part (env header + context files) that must stay current — sent as a leading input item under a bound
 * agent (which can't take request `instructions`). Both default so callers that don't care set only [systemPrompt].
 *
 * @param systemPrompt The full system prompt (ephemeral instructions).
 * @param tools The list of tools available to the model.
 * @param modelName The name of the model to use.
 * @param temperature The temperature to use for the model, if applicable.
 * @param baseSystemPrompt The stable part baked into a persisted agent; defaults to the whole [systemPrompt].
 * @param dynamicPreamble The per-turn dynamic part (env header + context files); empty when there is none.
 */
data class AgentContext(
    val systemPrompt: String,
    val tools: List<ToolSpec>,
    val modelName: String,
    val temperature: Double? = null,
    val baseSystemPrompt: String = systemPrompt,
    val dynamicPreamble: String = "",
)
