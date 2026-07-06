package com.konductor.core.models

data class ToolResult(
    val callId: String,
    val output: String,
    val isError: Boolean = false,
    val truncatedBytes: Int = 0
)
