package com.konductor.provider.inference

import com.konductor.core.models.Entry
import com.konductor.core.models.ToolSpec

data class InferenceRequest(
    val model: String,
    val systemPrompt: String,
    val history: List<Entry>,
    val tools: List<ToolSpec>,
    val temperature: Double? = null,
    /**
     * The dynamic preamble (env header + context files). Sent as a leading developer input item by the
     * agent-bound inference client (whose baked instructions can't be updated per turn); ignored by the ephemeral
     * client, where [systemPrompt] already carries it.
     */
    val dynamicPreamble: String = "",
)
