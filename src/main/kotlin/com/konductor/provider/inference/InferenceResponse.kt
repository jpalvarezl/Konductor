package com.konductor.provider.inference

import com.konductor.core.models.ToolCall
import com.konductor.core.models.Usage

data class InferenceResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val usage: Usage?,
)
