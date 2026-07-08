package com.konductor.core.models

import kotlinx.serialization.Serializable

@Serializable
data class ToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String
)
