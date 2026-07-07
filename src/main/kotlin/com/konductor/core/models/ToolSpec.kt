package com.konductor.core.models

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)
