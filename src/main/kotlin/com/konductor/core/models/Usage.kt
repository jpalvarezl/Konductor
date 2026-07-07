package com.konductor.core.models

data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
)
