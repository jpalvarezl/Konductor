package com.konductor.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A tool advertised to the model: [name] + [description] + a JSON-schema [parameters] object. [parameters] is
 * a [JsonObject] so the spec is safely serializable and unambiguous to map onto the
 * SDK's function schema. See docs/spec/tools.md.
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
