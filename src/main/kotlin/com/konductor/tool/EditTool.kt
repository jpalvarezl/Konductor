package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `edit` — replace exactly one occurrence of `oldString` with `newString` in a file. Refuses when the match
 * is missing (nothing to do) or non-unique (ambiguous), forcing the model to add surrounding context. Both
 * strings are treated literally (no regex). cwd-contained. See docs/spec/tools.md.
 */
class EditTool : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Replace an exact, unique occurrence of oldString with newString in a file.",
        parameters = objectSchema(
            required = listOf("path", "oldString", "newString"),
            properties = mapOf(
                "path" to prop("string", "File path relative to the working directory."),
                "oldString" to prop("string", "Exact text to replace; must occur exactly once in the file."),
                "newString" to prop("string", "Replacement text."),
            ),
        ),
    )

    @Serializable
    private data class Args(val path: String, val oldString: String, val newString: String)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val path = resolveInCwd(ctx.cwd, args.path)

        fun error(message: String) = ToolResult(call.callId, message, isError = true)

        if (!path.exists() || !path.isRegularFile()) return error("edit: no such file: ${args.path}")
        if (args.oldString.isEmpty()) return error("edit: oldString must not be empty")

        val text = withContext(Dispatchers.IO) { path.readText() }
        when (countOccurrences(text, args.oldString)) {
            0 -> return error("edit: oldString not found in ${args.path}")
            1 -> Unit
            else -> return error("edit: oldString is not unique in ${args.path}; add surrounding context to disambiguate")
        }

        val updated = text.replaceFirst(args.oldString, args.newString)
        withContext(Dispatchers.IO) { path.writeText(updated) }
        return ToolResult(call.callId, "edited ${displayPath(ctx.cwd, path)}: replaced 1 occurrence")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var index = haystack.indexOf(needle)
        var count = 0
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    companion object {
        const val NAME = "edit"
    }
}
