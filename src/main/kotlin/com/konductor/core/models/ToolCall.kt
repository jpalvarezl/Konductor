package com.konductor.core.models

data class ToolCall(
    val callId: String,
    val name: String,
    val arguments: Map<String, Any>
)
