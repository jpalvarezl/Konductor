package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * `grep` — regex-search file contents under a base directory (default: the working directory), optionally
 * restricted by a file glob. Emits `path:lineNumber:line` for each match (paths relative to cwd). Binary
 * files are skipped and the match count is capped. See docs/spec/tools.md.
 */
class GrepTool : Tool {
    override val spec = ToolSpec(
        name = "grep",
        description = "Regex-search file contents; returns matching lines as path:lineNumber:line.",
        parameters = objectSchema(
            required = listOf("pattern"),
            properties = mapOf(
                "pattern" to prop("string", "Regular expression to search for in each line."),
                "path" to prop("string", "Base directory (or single file) to search, relative to cwd (default '.')."),
                "glob" to prop("string", "Optional glob to restrict which files are searched, e.g. **/*.kt."),
            ),
        ),
    )

    @Serializable
    private data class Args(val pattern: String, val path: String? = null, val glob: String? = null)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val root = ctx.cwd.toAbsolutePath().normalize()
        val base = resolveInCwd(ctx.cwd, args.path ?: ".")
        if (!base.exists()) return ToolResult(call.callId, "grep: no such path: ${args.path}", isError = true)

        val regex = runCatching { Regex(args.pattern) }.getOrElse {
            return ToolResult(call.callId, "grep: invalid regex: ${it.message}", isError = true)
        }
        val globMatcher = args.glob?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val matches = withContext(Dispatchers.IO) {
            val out = mutableListOf<String>()
            Files.walk(base).use { stream ->
                val files = stream.filter { Files.isRegularFile(it) }.iterator()
                while (files.hasNext() && out.size < MAX_MATCHES) {
                    val file = files.next()
                    val rel = root.relativize(file)
                    if (globMatcher != null && !globMatcher.matches(rel)) continue

                    val bytes = file.readBytes()
                    if (bytes.any { it == 0.toByte() }) continue // skip binaries

                    var lineNumber = 0
                    for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
                        lineNumber++
                        if (regex.containsMatchIn(line)) {
                            out += "$rel:$lineNumber:$line"
                            if (out.size >= MAX_MATCHES) break
                        }
                    }
                }
            }
            out
        }

        return ToolResult(call.callId, matches.joinToString("\n").ifEmpty { "(no matches)" })
    }

    private companion object {
        const val MAX_MATCHES = 300
    }
}
