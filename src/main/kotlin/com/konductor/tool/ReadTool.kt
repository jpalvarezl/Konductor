package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * `read` — return a UTF-8 text file (optionally a line range) with 1-based line numbers so the model can cite
 * exact lines. Binary / non-UTF-8 files are refused rather than dumped as garbage. See docs/spec/tools.md.
 */
class ReadTool : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Read a UTF-8 text file, optionally a line range, returned with 1-based line numbers.",
        parameters = objectSchema(
            required = listOf("path"),
            properties = mapOf(
                "path" to prop("string", "File path relative to the working directory."),
                "offset" to prop("integer", "1-based line number to start reading from (default 1)."),
                "limit" to prop("integer", "Maximum number of lines to return (default: to end of file)."),
            ),
        ),
    )

    @Serializable
    private data class Args(val path: String, val offset: Int? = null, val limit: Int? = null)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val path = resolveInCwd(ctx.cwd, args.path)

        fun error(message: String) = ToolResult(call.callId, message, isError = true)

        if (!path.exists()) return error("read: no such file: ${args.path}")
        if (!path.isRegularFile()) return error("read: not a regular file: ${args.path}")

        val bytes = withContext(Dispatchers.IO) { path.readBytes() }
        if (bytes.any { it == 0.toByte() }) return error("read: binary file not shown: ${args.path}")
        val content = decodeUtf8OrNull(bytes) ?: return error("read: not a UTF-8 text file: ${args.path}")

        val lines = content.split("\n")
        val start = (args.offset ?: 1).coerceAtLeast(1)
        val body = lines.asSequence()
            .drop(start - 1)
            .let { seq -> args.limit?.let { seq.take(it.coerceAtLeast(0)) } ?: seq }
            .mapIndexed { index, line -> "${(start + index).toString().padStart(6)}\t$line" }
            .joinToString("\n")

        return ToolResult(call.callId, body)
    }

    companion object {
        const val NAME = "read"
    }
}
