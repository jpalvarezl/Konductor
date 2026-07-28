package com.konductor.provider.inference

import com.konductor.core.models.ToolCall
import com.konductor.core.models.Usage

/** Application result of one Foundry Responses call. */
data class FoundryResponsesResult(
    val text: String,
    val toolCalls: List<ToolCall>,
    val usage: Usage?,
)
