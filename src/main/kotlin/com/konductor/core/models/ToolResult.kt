package com.konductor.core.models

import kotlinx.serialization.Serializable

@Serializable
data class ToolResult(
    val callId: String,
    val output: String,
    val isError: Boolean = false,
    val truncatedBytes: Int = 0
)
