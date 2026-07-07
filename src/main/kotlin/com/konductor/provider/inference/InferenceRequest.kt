package com.konductor.provider.inference

import com.konductor.core.models.Entry
import com.konductor.core.models.ToolSpec

data class InferenceRequest(
    val model: String,
    val systemPrompt: String,
    val history: List<Entry>,
    val tools: List<ToolSpec>,
    val temperature: Double? = null,
)
