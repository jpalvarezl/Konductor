package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolRender(
    val summary: String,
    val rawDetail: String? = null,
)

fun renderToolCall(call: ToolCall): ToolRender {
    val args = parseArgs(call.argumentsJson)
    val summary = when (call.name) {
        "read" -> "read ${pathLabel(args)}${rangeLabel(args)}"
        "ls" -> "ls ${pathLabel(args, default = ".")}"
        "find" -> "find ${stringArg(args, "pattern") ?: "(pattern)"}${inPath(args)}"
        "grep" -> "grep ${quote(stringArg(args, "pattern") ?: "(pattern)")}${inPath(args)}"
        "bash" -> "bash ${compact(stringArg(args, "command") ?: "(command)", 80)}"
        "write" -> "write ${pathLabel(args)}"
        "edit" -> "edit ${pathLabel(args)}"
        else -> "${call.name} ${argsToLabel(args) ?: compact(call.argumentsJson)}"
    }
    return ToolRender(summary.trim(), rawDetail = call.argumentsJson)
}

fun renderToolResult(call: ToolCall, result: ToolResult): ToolRender {
    val callSummary = renderToolCall(call).summary
    if (result.isError) {
        return ToolRender("$callSummary failed: ${compact(firstLine(result.output))}", rawDetail = result.output)
    }

    val args = parseArgs(call.argumentsJson)
    val summary = when (call.name) {
        "read" -> "${renderToolCall(call).summary} (${lineCount(result.output)} lines)"
        "ls" -> "${renderToolCall(call).summary} (${itemCount(result.output)} entries)"
        "find" -> "${renderToolCall(call).summary} (${matchCount(result.output)} matches)"
        "grep" -> "${renderToolCall(call).summary} (${matchCount(result.output)} matches)"
        "bash" -> "${renderToolCall(call).summary} (${firstLine(result.output).ifBlank { "done" }})"
        "write" -> "write ${pathLabel(args)} (${firstLine(result.output).substringBefore(" to ").ifBlank { "done" }})"
        "edit" -> "edit ${pathLabel(args)} (1 change)"
        else -> "$callSummary: ${compact(firstLine(result.output))}"
    }
    val suffix = if (result.truncatedBytes > 0) " • truncated" else ""
    return ToolRender(summary + suffix, rawDetail = result.output)
}

fun renderToolResult(name: String, result: ToolResult): ToolRender {
    val marker = if (result.isError) "failed" else "done"
    return ToolRender("$name $marker: ${compact(firstLine(result.output))}", rawDetail = result.output)
}

private fun parseArgs(argumentsJson: String): JsonObject? =
    runCatching { toolJson.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

private fun stringArg(args: JsonObject?, name: String): String? =
    args?.get(name)?.jsonPrimitiveOrNull()?.content

private fun intArg(args: JsonObject?, name: String): Int? =
    args?.get(name)?.jsonPrimitiveOrNull()?.intOrNull

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun pathLabel(args: JsonObject?, default: String = "(path)"): String {
    val path = stringArg(args, "path") ?: default
    return path.replace('\\', '/').substringAfterLast('/').ifEmpty { path }
}

private fun rangeLabel(args: JsonObject?): String {
    val offset = intArg(args, "offset") ?: return ""
    val limit = intArg(args, "limit")
    return if (limit == null) ":$offset" else ":$offset-${offset + limit - 1}"
}

private fun inPath(args: JsonObject?): String =
    stringArg(args, "path")?.let { " in $it" } ?: ""

private fun argsToLabel(args: JsonObject?): String? {
    if (args == null || args.isEmpty()) return null
    return args.entries.joinToString(" ") { (key, value) ->
        "$key=${compact(value.toString(), 40)}"
    }
}

private fun firstLine(text: String): String = text.lineSequence().firstOrNull().orEmpty()

private fun lineCount(output: String): Int =
    output.lineSequence().count { it.isNotBlank() }

private fun itemCount(output: String): Int =
    if (output == "(empty directory)") 0 else lineCount(output)

private fun matchCount(output: String): Int =
    if (output == "(no matches)") 0 else lineCount(output)

private fun quote(text: String): String = "\"${compact(text, 40)}\""

private fun compact(text: String, max: Int = 120): String {
    val oneLine = text.replace("\n", " ").trim()
    return if (oneLine.length > max) oneLine.take(max - 1) + "…" else oneLine
}
