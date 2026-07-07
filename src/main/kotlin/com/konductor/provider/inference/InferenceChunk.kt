package com.konductor.provider.inference

import com.konductor.provider.AgentEvent

data class InferenceChunk(
    val textDelta: String? = null,
)
