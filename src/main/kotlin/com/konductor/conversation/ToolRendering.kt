package com.konductor.conversation

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.i18n.AppStrings
import com.konductor.tool.BashTool
import com.konductor.tool.EditTool
import com.konductor.tool.FindTool
import com.konductor.tool.GrepTool
import com.konductor.tool.LsTool
import com.konductor.tool.ReadTool
import com.konductor.tool.WriteTool
import com.konductor.tool.toolJson
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

fun renderToolCall(
    call: ToolCall,
    strings: AppStrings = AppStrings.english(),
): ToolRender {
    val args = parseArgs(call.argumentsJson)
    val summary = when (call.name) {
        ReadTool.NAME -> strings.toolRead(call.name, pathLabel(args, strings = strings), rangeLabel(args))
        LsTool.NAME -> strings.toolWithArgument(call.name, pathLabel(args, default = ".", strings = strings))
        FindTool.NAME -> strings.toolWithLocation(
            call.name,
            stringArg(args, "pattern") ?: strings.toolPatternPlaceholder,
            inPath(args, strings),
        )
        GrepTool.NAME -> strings.toolWithLocation(
            call.name,
            quote(stringArg(args, "pattern") ?: strings.toolPatternPlaceholder),
            inPath(args, strings),
        )
        BashTool.NAME -> strings.toolWithArgument(
            call.name,
            compact(stringArg(args, "command") ?: strings.toolCommandPlaceholder, 80),
        )
        WriteTool.NAME -> strings.toolWithArgument(call.name, pathLabel(args, strings = strings))
        EditTool.NAME -> strings.toolWithArgument(call.name, pathLabel(args, strings = strings))
        else -> strings.toolFallback(call.name, argsToLabel(args) ?: compact(call.argumentsJson))
    }
    return ToolRender(summary.trim(), rawDetail = call.argumentsJson)
}

fun renderToolResult(
    call: ToolCall,
    result: ToolResult,
    strings: AppStrings = AppStrings.english(),
): ToolRender {
    val callSummary = renderToolCall(call, strings).summary
    if (result.isError) {
        return ToolRender(
            strings.toolCallFailed(callSummary, compact(firstLine(result.output))),
            rawDetail = result.output,
        )
    }

    val args = parseArgs(call.argumentsJson)
    val summary = when (call.name) {
        ReadTool.NAME -> strings.toolCount(callSummary, lineCount(result.output), strings.toolLinesUnit())
        LsTool.NAME -> strings.toolCount(callSummary, itemCount(result.output), strings.toolEntriesUnit())
        FindTool.NAME -> strings.toolCount(callSummary, matchCount(result.output), strings.toolMatchesUnit())
        GrepTool.NAME -> strings.toolCount(callSummary, matchCount(result.output), strings.toolMatchesUnit())
        BashTool.NAME -> strings.toolDetail(callSummary, firstLine(result.output).ifBlank { strings.toolDone })
        WriteTool.NAME -> strings.toolWrite(
            call.name,
            pathLabel(args, strings = strings),
            firstLine(result.output).substringBefore(" to ").ifBlank { strings.toolDone },
        )
        EditTool.NAME -> strings.toolEdit(call.name, pathLabel(args, strings = strings))
        else -> strings.toolFallbackResult(callSummary, compact(firstLine(result.output)))
    }
    val suffix = if (result.truncatedBytes > 0) strings.toolTruncated else ""
    return ToolRender(summary + suffix, rawDetail = result.output)
}

fun renderToolResult(
    name: String,
    result: ToolResult,
    strings: AppStrings = AppStrings.english(),
): ToolRender {
    val marker = if (result.isError) strings.toolFailed else strings.toolDone
    return ToolRender(strings.toolUnknown(name, marker, compact(firstLine(result.output))), rawDetail = result.output)
}

private fun parseArgs(argumentsJson: String): JsonObject? =
    runCatching { toolJson.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()

private fun stringArg(args: JsonObject?, name: String): String? =
    args?.get(name)?.jsonPrimitiveOrNull()?.content

private fun intArg(args: JsonObject?, name: String): Int? =
    args?.get(name)?.jsonPrimitiveOrNull()?.intOrNull

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

private fun pathLabel(args: JsonObject?, default: String? = null, strings: AppStrings): String {
    val path = stringArg(args, "path") ?: default ?: strings.toolPathPlaceholder
    return path.replace('\\', '/').substringAfterLast('/').ifEmpty { path }
}

private fun rangeLabel(args: JsonObject?): String {
    val offset = intArg(args, "offset") ?: return ""
    val limit = intArg(args, "limit")
    return if (limit == null) ":$offset" else ":$offset-${offset + limit - 1}"
}

private fun inPath(args: JsonObject?, strings: AppStrings): String =
    stringArg(args, "path")?.let(strings::toolInPath) ?: ""

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
