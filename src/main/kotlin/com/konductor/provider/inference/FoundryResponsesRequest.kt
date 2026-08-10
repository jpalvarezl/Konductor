package com.konductor.provider.inference

import com.konductor.core.models.Entry
import com.konductor.core.models.ToolSpec

/** Application request for one Foundry Responses call. */
data class FoundryResponsesRequest(
    val model: String,
    val systemPrompt: String,
    val history: List<Entry>,
    val tools: List<ToolSpec>,
    val temperature: Double? = null,
    /**
     * The dynamic preamble (rendered context block followed by environment). Sent as one leading developer item by the
     * PromptAgent-bound Responses adapter (whose baked instructions cannot be updated per turn); ignored by the
     * ephemeral adapter, where [systemPrompt] already carries it.
     */
    val dynamicPreamble: String = "",
)
