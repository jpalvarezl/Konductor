package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * `ls` — list a directory's immediate entries (default: the working directory), each tagged `[d]`/`[f]` and
 * sorted directories-first then by name. See docs/spec/tools.md.
 */
class LsTool : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "List the immediate entries of a directory (defaults to the working directory).",
        parameters = objectSchema(
            required = emptyList(),
            properties = mapOf(
                "path" to prop("string", "Directory path relative to the working directory (default '.')."),
            ),
        ),
    )

    @Serializable
    private data class Args(val path: String? = null)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val dir = resolveInCwd(ctx.cwd, args.path ?: ".")

        if (!dir.isDirectory()) return ToolResult(call.callId, "ls: not a directory: ${args.path ?: "."}", isError = true)

        val entries = withContext(Dispatchers.IO) { dir.listDirectoryEntries() }
            .sortedWith(compareByDescending<java.nio.file.Path> { it.isDirectory() }.thenBy { it.name.lowercase() })
            .joinToString("\n") { "${if (it.isDirectory()) "[d]" else "[f]"} ${it.name}" }

        return ToolResult(call.callId, entries.ifEmpty { "(empty directory)" })
    }

    companion object {
        const val NAME = "ls"
    }
}
