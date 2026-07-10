package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * `write` — create or overwrite a UTF-8 file with the given content, creating parent directories as needed.
 * cwd-contained. See docs/spec/tools.md.
 */
class WriteTool : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Create or overwrite a UTF-8 text file with the given content (creates parent directories).",
        parameters = objectSchema(
            required = listOf("path", "content"),
            properties = mapOf(
                "path" to prop("string", "File path relative to the working directory."),
                "content" to prop("string", "Full file content to write."),
            ),
        ),
    )

    @Serializable
    private data class Args(val path: String, val content: String)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val path = resolveInCwd(ctx.cwd, args.path)

        withContext(Dispatchers.IO) {
            path.parent?.let { Files.createDirectories(it) }
            path.writeText(args.content)
        }

        val bytes = args.content.toByteArray(Charsets.UTF_8).size
        return ToolResult(call.callId, "wrote $bytes bytes to ${displayPath(ctx.cwd, path)}")
    }

    companion object {
        const val NAME = "write"
    }
}
