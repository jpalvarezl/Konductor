package com.konductor.tool

import com.konductor.core.models.ToolResult
import kotlinx.serialization.json.Json
import java.nio.file.Path

/** Shared lenient JSON reader for parsing tool argument payloads. */
internal val toolJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Default per-call output cap (docs/spec/tools.md). Tool output re-enters the model's context, so it is
 * bounded; beyond this the executor cuts it and appends a truncation marker.
 */
const val MAX_TOOL_OUTPUT_BYTES: Int = 16 * 1024

/**
 * Resolve [raw] against [cwd] and guarantee it stays inside it — rejecting `..` escapes and absolute paths
 * that point elsewhere. Returns the normalized absolute path.
 *
 * @throws IllegalArgumentException if the resolved path escapes [cwd].
 */
fun resolveInCwd(cwd: Path, raw: String): Path {
    val root = cwd.toAbsolutePath().normalize()
    val resolved = root.resolve(raw).normalize()
    require(resolved == root || resolved.startsWith(root)) {
        "path '$raw' escapes the working directory"
    }
    return resolved
}

/** Path displayed to the model: relative to [cwd] when inside it, else the absolute path. */
fun displayPath(cwd: Path, path: Path): String {
    val root = cwd.toAbsolutePath().normalize()
    val abs = path.toAbsolutePath().normalize()
    return if (abs.startsWith(root)) root.relativize(abs).toString().ifEmpty { "." } else abs.toString()
}

/**
 * Cap [result]'s output at [maxBytes] UTF-8 bytes, appending a truncation marker and recording the number of
 * dropped bytes, so the model can narrow its request instead of silently losing data.
 */
fun truncateToolResult(result: ToolResult, maxBytes: Int = MAX_TOOL_OUTPUT_BYTES): ToolResult {
    val bytes = result.output.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return result
    val shown = String(bytes, 0, maxBytes, Charsets.UTF_8)
    return result.copy(
        output = shown + "\n\n[output truncated: showed $maxBytes of ${bytes.size} bytes]",
        truncatedBytes = bytes.size - maxBytes,
    )
}

/** Build a JSON-schema `object` for a tool's parameters. */
internal fun objectSchema(
    required: List<String>,
    properties: Map<String, Map<String, Any>>,
): Map<String, Any> = mapOf(
    "type" to "object",
    "properties" to properties,
    "required" to required,
    "additionalProperties" to false,
)

/** Build a single JSON-schema property (`{ "type": ..., "description": ... }`). */
internal fun prop(type: String, description: String): Map<String, Any> =
    mapOf("type" to type, "description" to description)
