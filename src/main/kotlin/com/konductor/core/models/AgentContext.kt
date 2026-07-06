package com.konductor.core.models

/**
 * Everything a model sees *before* the transcript. Assembled from system prompt, context files, tool registry, etc.
 *
 * @param systemPrompt The system prompt to use for the model.
 * @param tools The list of tools available to the model.
 * @param modelName The name of the model to use.
 * @param temperature The temperature to use for the model, if applicable.
 */
data class AgentContext(
    val systemPrompt: String,
    val tools: List<ToolSpec>,
    val modelName: String,
    val temperature: Double? = null
)
