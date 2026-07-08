package com.konductor.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)
